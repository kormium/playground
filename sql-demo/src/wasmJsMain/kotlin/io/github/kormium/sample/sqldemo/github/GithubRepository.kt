@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sample.sqldemo.github

import io.github.kormium.AscDescOrder
import io.github.kormium.Column
import io.github.kormium.Expression
import io.github.kormium.Query
import io.github.kormium.Selectable
import io.github.kormium.and
import io.github.kormium.count
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.ltEq
import io.github.kormium.query
import io.github.kormium.sqlite.wasm.createWorkerSqliteWasmDatabase
import io.github.kormium.sum
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** The licenses shown as filter chips (rest fold into "Other"); order = descending frequency. */
val LICENSES = listOf("MIT", "Apache-2.0", "GPL-3.0", "AGPL-3.0", "GPL-2.0", "BSD-3-Clause", "MPL-2.0")

/** "no push in 2 years" cutoff — abandoned repos are those whose last push predates this date. */
const val ABANDONED_BEFORE = "2024-07-11"

/** Bounds of the created-year range filter (inclusive); the full span means "no year filter". */
const val YEAR_MIN = 2009
const val YEAR_MAX = 2025

/** Star-count buckets for the iceberg chart: (label, inclusive low, inclusive high). */
val STAR_BUCKETS: List<Triple<String, Int, Int>> = listOf(
    Triple("1–9", 1, 9),
    Triple("10–49", 10, 49),
    Triple("50–99", 50, 99),
    Triple("100–499", 100, 499),
    Triple("500–999", 500, 999),
    Triple("1k–5k", 1_000, 4_999),
    Triple("5k+", 5_000, Int.MAX_VALUE),
)

/**
 * Curated topic families for the "topic trends over time" chart, each folding its common aliases
 * into one line. A repo counts toward a family if any of its topics is in the alias set.
 */
val TOPIC_GROUPS: List<Pair<String, Set<String>>> = listOf(
    "Compose" to setOf("jetpack-compose", "compose", "compose-multiplatform", "jetpack-compose-multiplatform", "composemultiplatform"),
    "Multiplatform" to setOf("kotlin-multiplatform", "multiplatform", "kmp", "kmm", "kotlin-multiplatform-mobile", "kotlin-multiplatform-library"),
    "Android" to setOf("android", "android-app", "android-application"),
    "Spring" to setOf("spring-boot", "spring", "springboot"),
    "Ktor" to setOf("ktor"),
    "Coroutines" to setOf("coroutines", "kotlin-coroutines"),
)

/** One curated topic family's per-year share (%), for the trends chart. */
data class TopicTrend(val topic: String, val sharePctByYear: List<Pair<Int, Int>>)

/**
 * Structural facts about the whole dataset, computed once while the feed streams in (no per-filter
 * query). These describe the ecosystem over time, not the current filter slice.
 */
data class EcosystemStats(
    val years: List<Int>,                          // year axis shared by the trend charts
    val cumulativeByYear: List<Pair<Int, Long>>,   // total repos in existence by year (cumsum)
    val licensedPctByYear: List<Pair<Int, Int>>,   // % of that year's repos carrying a license
    val activePctByYear: List<Pair<Int, Int>>,     // % of that year's repos still pushed within 2y
    val activeAbandonedByYear: List<Triple<Int, Long, Long>>, // (year, active, abandoned)
    val topicTrends: List<TopicTrend>,             // curated topic families' share by year
    val lorenz: List<Pair<Float, Float>>,          // cumulative repo share -> cumulative star share
    val top1PctStarShare: Int,                     // % of all stars held by the top 1% of repos
    val top10PctStarShare: Int,
)

/** Repo-table sort columns, mapped to real Kormium columns in the repository. */
enum class RepoSort { NAME, STARS, CREATED, PUSHED }

private fun RepoSort.column(): Column<*, *, *> = when (this) {
    RepoSort.NAME -> Repos.name
    RepoSort.STARS -> Repos.stars
    RepoSort.CREATED -> Repos.createdAt
    RepoSort.PUSHED -> Repos.pushedAt
}

/** Liveness filter for the dashboard. */
enum class RepoStatus { ALL, ACTIVE, ABANDONED }

/** One repository row for the UI table. */
data class RepoRow(
    val owner: String,
    val name: String,
    val stars: Int,
    val forks: Int,
    val license: String,
    val createdAt: String,
    val pushedAt: String,
    val topics: String,
)

/** One timed query for the on-screen log — the real SQL text, not a description. */
data class RepoLogEntry(val label: String, val sql: String, val ms: Long)

data class RepoTimedRows(val rows: List<RepoRow>, val entry: RepoLogEntry)
data class RepoTimedValue<T>(val value: T, val entry: RepoLogEntry)

/**
 * Everything the dashboard shows, rolled up client-side from ONE grouped query (see [dashboard]):
 * the group axes are tiny (16 years x a handful of licenses), so one scan replaces three.
 */
data class RepoDashboard(
    val byYear: Map<Int, Long>,        // repos created per year — the hockey stick
    val starsByYear: Map<Int, Long>,   // total stars per creation year
    val byLicense: Map<String, Long>,  // repos per license
    val topOwners: List<Pair<String, Long>>, // owners with the most matching repos
    val byBucket: List<Pair<String, Long>>,  // repos per star-count bucket — the iceberg
    val count: Long,
    val stars: Long,
    val forks: Long,
    val abandoned: Long,               // matching repos with no push in 2 years
    val avgStars: Double,
)

private data class Timed<T>(val value: T, val ms: Long)

private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
    val mark = TimeSource.Monotonic.markNow()
    val value = block()
    return Timed(value, mark.elapsedNow().inWholeMilliseconds)
}

private val reposIndexes = listOf(
    """CREATE INDEX IF NOT EXISTS "repos_stars" ON "repos" ("stars")""",
    """CREATE INDEX IF NOT EXISTS "repos_year" ON "repos" ("createdYear")""",
    """CREATE INDEX IF NOT EXISTS "repos_license" ON "repos" ("license")""",
    """CREATE INDEX IF NOT EXISTS "repos_pushed" ON "repos" ("pushedAt")""",
    """CREATE INDEX IF NOT EXISTS "repos_created" ON "repos" ("createdAt")""",
    """CREATE INDEX IF NOT EXISTS "repos_name" ON "repos" ("name")""",
    // Covers the dashboard cube (GROUP BY createdYear, license + stars aggregate) with an
    // index-ordered scan instead of a temp-B-tree sort of the filtered set.
    """CREATE INDEX IF NOT EXISTS "repos_cube" ON "repos" ("createdYear", "license", "stars")""",
)

/**
 * Data layer over the real Kotlin-ecosystem dataset (283k GitHub repos), backed by an in-memory
 * SQLite hosted in a Worker — the same in-memory, single-connection, Worker-hosted configuration
 * SalesRepository landed on. The dataset is streamed in as a TSV feed and re-inserted through the
 * Kormium DSL: the browser can't open a prebuilt SQLite file (no deserialize in the wasm driver),
 * so it ships the data and rebuilds the database in the tab — exactly what the synthetic generator
 * does, only the rows come from GitHub instead of Random.
 */
class GithubRepository private constructor(
    private val db: SuspendDatabase<KotlinEco>,
) {

    /**
     * The most common GitHub topics across the whole dataset, counted once while streaming the feed
     * in (every topic string passes through [load] anyway). Static — it describes the ecosystem, not
     * the current filter — so it needs no per-refresh query.
     */
    var topTopics: List<Pair<String, Long>> = emptyList()
        private set

    /** Ecosystem-wide trend facts, computed once at [load]; null until the dataset is loaded. */
    var ecosystem: EcosystemStats? = null
        private set

    private fun filter(minStars: Int, license: String?, status: RepoStatus, minYear: Int, maxYear: Int): Expression {
        var e: Expression = Repos.stars gtEq minStars
        if (license != null) e = e and (Repos.license eq license)
        if (minYear > YEAR_MIN) e = e and (Repos.createdYear gtEq minYear)
        if (maxYear < YEAR_MAX) e = e and (Repos.createdYear ltEq maxYear)
        when (status) {
            RepoStatus.ACTIVE -> e = e and (Repos.pushedAt gtEq ABANDONED_BEFORE)
            RepoStatus.ABANDONED -> e = e and (Repos.pushedAt ltEq ABANDONED_BEFORE)
            RepoStatus.ALL -> {}
        }
        return e
    }

    private fun filterSql(minStars: Int, license: String?, status: RepoStatus, minYear: Int, maxYear: Int): String {
        val parts = mutableListOf("stars >= $minStars")
        if (license != null) parts += "license = '$license'"
        if (minYear > YEAR_MIN) parts += "createdYear >= $minYear"
        if (maxYear < YEAR_MAX) parts += "createdYear <= $maxYear"
        when (status) {
            RepoStatus.ACTIVE -> parts += "pushedAt >= '$ABANDONED_BEFORE'"
            RepoStatus.ABANDONED -> parts += "pushedAt <= '$ABANDONED_BEFORE'"
            RepoStatus.ALL -> {}
        }
        return parts.joinToString(" AND ")
    }

    /**
     * Stream the TSV feed in and rebuild the table: parse, batch-insert, index, ANALYZE. [onProgress]
     * reports rows inserted; [onPhase] reports post-insert phases (fetch, index, analyze) that move
     * no row counter. A macrotask yield between batches lets the browser paint. Returns total ms.
     */
    suspend fun load(
        // 13 columns per row × batchSize bound params must stay under SQLite's 32,766 variable
        // limit (a wider row than the 8-column synthetic table, so a smaller batch than its 4,000).
        batchSize: Int = 2_000,
        onProgress: (Int) -> Unit = {},
        onPhase: (String) -> Unit = {},
    ): Long {
        val mark = TimeSource.Monotonic.markNow()
        onPhase("Downloading the Kotlin-ecosystem dataset (~9 MB)…")
        val text = fetchGzipText("repos.tsv.gz")
        onPhase("Parsing ${text.length / 1_048_576} MB…")
        val lines = text.split('\n')

        val topicCounts = HashMap<String, Long>()
        // Ecosystem accumulators (whole dataset, keyed by creation year).
        val reposByYear = HashMap<Int, Long>()
        val licensedByYear = HashMap<Int, Long>()
        val activeByYear = HashMap<Int, Long>()
        val groupByYear = TOPIC_GROUPS.associate { it.first to HashMap<Int, Long>() }
        val starList = ArrayList<Int>(300_000)
        db.suspendTransaction {
            reposIndexes.forEach { Repos.execSql(it.replace("CREATE INDEX IF NOT EXISTS", "DROP INDEX IF EXISTS").substringBefore(" ON ")) }
            Repos.execSql("""DELETE FROM "repos"""")
            onPhase("Inserting rows…")
            var i = 1 // skip header
            var inserted = 0
            while (i < lines.size) {
                val batch = ArrayList<Repo>(batchSize)
                while (batch.size < batchSize && i < lines.size) {
                    val line = lines[i++]
                    if (line.isEmpty()) continue
                    val f = line.split('\t')
                    if (f.size < 13) continue
                    val year = f[8].toInt()
                    reposByYear[year] = (reposByYear[year] ?: 0L) + 1
                    if (f[10].isNotEmpty()) licensedByYear[year] = (licensedByYear[year] ?: 0L) + 1
                    if (f[9] >= ABANDONED_BEFORE) activeByYear[year] = (activeByYear[year] ?: 0L) + 1
                    starList.add(f[3].toInt())
                    val topicList = f[12]
                    if (topicList.isNotEmpty()) {
                        val topicSet = topicList.split(',').filter { it.isNotEmpty() }
                        for (t in topicSet) topicCounts[t] = (topicCounts[t] ?: 0L) + 1
                        for ((group, aliases) in TOPIC_GROUPS) {
                            if (topicSet.any { it in aliases }) {
                                val m = groupByYear.getValue(group)
                                m[year] = (m[year] ?: 0L) + 1
                            }
                        }
                    }
                    batch.add(
                        Repo().apply {
                            id = f[0].toLong()
                            owner = f[1]; name = f[2]
                            stars = f[3].toInt(); forks = f[4].toInt(); openIssues = f[5].toInt()
                            sizeKb = f[6].toInt()
                            createdAt = f[7]; createdYear = f[8].toInt(); pushedAt = f[9]
                            license = f[10]; archived = f[11].toInt(); topics = topicList
                        },
                    )
                }
                if (batch.isNotEmpty()) {
                    Repos.insertAll(batch)
                    inserted += batch.size
                    onProgress(inserted)
                }
                delay(1) // let the browser paint the progress bar
            }
            reposIndexes.forEachIndexed { idx, sql ->
                onPhase("Building index ${idx + 1}/${reposIndexes.size}…")
                Repos.execSql(sql)
            }
            onPhase("Analyzing (query planner statistics)…")
            Repos.execSql("ANALYZE")
        }
        topTopics = topicCounts.entries.sortedByDescending { it.value }.take(15).map { it.key to it.value }
        ecosystem = computeEcosystem(reposByYear, licensedByYear, activeByYear, groupByYear, starList)
        return mark.elapsedNow().inWholeMilliseconds
    }

    /**
     * The dashboard, from three grouped scans: the (year, license) cube behind the year/license/KPI
     * roll-up, a GROUP BY owner for the top-owners chart, and a filtered count of abandoned repos.
     * The logged entry is the cube query; all three are indexed and finish in single-digit ms.
     */
    suspend fun dashboard(
        minStars: Int, license: String?, status: RepoStatus,
        minYear: Int = YEAR_MIN, maxYear: Int = YEAR_MAX,
    ): RepoTimedValue<RepoDashboard> {
        val expr = filter(minStars, license, status, minYear, maxYear)
        val starSum = Repos.stars.sum()
        val forkSum = Repos.forks.sum()
        val cnt = count()
        val sql = "SELECT createdYear, license, COUNT(*), SUM(stars), SUM(forks) FROM repos " +
            "WHERE ${filterSql(minStars, license, status, minYear, maxYear)} GROUP BY createdYear, license"
        val t = timed {
            db.suspendTransaction(readOnly = true) {
                Repos.query().where(expr)
                    .groupBy(Repos.createdYear, Repos.license)
                    .select(Repos.createdYear, Repos.license, cnt, starSum, forkSum)
            }
        }
        val ownerRows = db.suspendTransaction(readOnly = true) {
            Repos.query().where(expr).groupBy(Repos.owner).select(Repos.owner, cnt)
        }
        val abandonedRows = db.suspendTransaction(readOnly = true) {
            Repos.query().where(expr and (Repos.pushedAt ltEq ABANDONED_BEFORE)).select(cnt)
        }
        // The iceberg: one indexed count per star-count bucket (respecting the active filter).
        val byBucket = STAR_BUCKETS.map { (label, lo, hi) ->
            val rows = db.suspendTransaction(readOnly = true) {
                Repos.query().where(expr and (Repos.stars gtEq lo) and (Repos.stars ltEq hi)).select(cnt)
            }
            label to (rows.firstOrNull()?.get(cnt) ?: 0L)
        }

        val byYear = mutableMapOf<Int, Long>()
        val starsByYear = mutableMapOf<Int, Long>()
        val byLicense = mutableMapOf<String, Long>()
        var totalCount = 0L
        var totalStars = 0L
        var totalForks = 0L
        for (row in t.value) {
            val c = row[cnt]
            val s = row.getOrNull(starSum) ?: 0L
            val fk = row.getOrNull(forkSum) ?: 0L
            val year = row[Repos.createdYear]
            val lic = row[Repos.license].ifEmpty { "(none)" }
            byYear[year] = (byYear[year] ?: 0L) + c
            starsByYear[year] = (starsByYear[year] ?: 0L) + s
            byLicense[lic] = (byLicense[lic] ?: 0L) + c
            totalCount += c
            totalStars += s
            totalForks += fk
        }
        val topOwners = ownerRows.map { it[Repos.owner] to it[cnt] }
            .sortedByDescending { it.second }.take(10)
        val abandoned = abandonedRows.firstOrNull()?.get(cnt) ?: 0L
        val avg = if (totalCount > 0) totalStars.toDouble() / totalCount else 0.0
        return RepoTimedValue(
            RepoDashboard(byYear, starsByYear, byLicense, topOwners, byBucket, totalCount, totalStars, totalForks, abandoned, avg),
            RepoLogEntry("Dashboard cube (year × license + KPI, one scan)", sql, t.ms),
        )
    }

    /** A live, sorted sample of the matching repos. */
    suspend fun sampleRows(
        minStars: Int, license: String?, status: RepoStatus,
        sort: RepoSort, ascending: Boolean, limit: Int = 100,
        minYear: Int = YEAR_MIN, maxYear: Int = YEAR_MAX,
    ): RepoTimedRows {
        val direction = if (ascending) "ASC" else "DESC"
        val col = when (sort) {
            RepoSort.NAME -> "name"; RepoSort.STARS -> "stars"
            RepoSort.CREATED -> "createdAt"; RepoSort.PUSHED -> "pushedAt"
        }
        val sql = "SELECT * FROM repos WHERE ${filterSql(minStars, license, status, minYear, maxYear)} " +
            "ORDER BY $col $direction LIMIT $limit"
        val t = timed {
            db.suspendTransaction(readOnly = true) {
                Repos.find(
                    Query(
                        whereExpression = filter(minStars, license, status, minYear, maxYear),
                        orderBy = mapOf<Selectable<*>, AscDescOrder>(
                            sort.column() to if (ascending) AscDescOrder.ASC else AscDescOrder.DESC,
                        ),
                        limit = limit.toUInt(),
                    ),
                )
            }.map {
                RepoRow(it.owner, it.name, it.stars, it.forks, it.license, it.createdAt, it.pushedAt, it.topics)
            }
        }
        return RepoTimedRows(t.value, RepoLogEntry("Rows", sql, t.ms))
    }

    companion object {
        suspend fun open(): GithubRepository {
            val db: SuspendDatabase<KotlinEco> = createWorkerSqliteWasmDatabase()
            db.suspendTransaction { Repos.execSql(reposDdl) }
            return GithubRepository(db)
        }
    }
}

/**
 * Roll the per-year accumulators and the star list into the trend facts. Years are limited to those
 * with a meaningful sample (>= 50 repos) so early-year noise doesn't dominate the trend lines.
 */
private fun computeEcosystem(
    reposByYear: Map<Int, Long>,
    licensedByYear: Map<Int, Long>,
    activeByYear: Map<Int, Long>,
    groupByYear: Map<String, Map<Int, Long>>,
    starList: List<Int>,
): EcosystemStats {
    val years = reposByYear.filterValues { it >= 50 }.keys.sorted()

    var running = 0L
    val cumulative = reposByYear.keys.sorted().map { y ->
        running += reposByYear.getValue(y)
        y to running
    }

    fun pct(part: Long, whole: Long): Int = if (whole > 0) ((part * 100) / whole).toInt() else 0
    val licensedPct = years.map { y -> y to pct(licensedByYear[y] ?: 0L, reposByYear.getValue(y)) }
    val activePct = years.map { y -> y to pct(activeByYear[y] ?: 0L, reposByYear.getValue(y)) }
    val activeAbandoned = years.map { y ->
        val total = reposByYear.getValue(y)
        val active = activeByYear[y] ?: 0L
        Triple(y, active, total - active)
    }

    val topicTrends = TOPIC_GROUPS.map { (group, _) ->
        val m = groupByYear.getValue(group)
        TopicTrend(group, years.map { y -> y to pct(m[y] ?: 0L, reposByYear.getValue(y)) })
    }

    // Lorenz curve of stars: walk repos poorest-first, plot cumulative repo share vs star share.
    val sorted = starList.sorted()
    val n = sorted.size
    val totalStars = sorted.fold(0L) { acc, s -> acc + s }
    val lorenz = ArrayList<Pair<Float, Float>>()
    if (n > 0 && totalStars > 0) {
        val samples = 60
        var idx = 0
        var acc = 0L
        for (k in 0..samples) {
            val target = (n.toLong() * k / samples).toInt()
            while (idx < target) { acc += sorted[idx]; idx++ }
            lorenz.add(k.toFloat() / samples to acc.toFloat() / totalStars)
        }
    }
    // Concentration: share of all stars held by the richest 1% / 10% of repos.
    val desc = sorted.asReversed()
    fun topShare(fraction: Double): Int {
        if (n == 0 || totalStars == 0L) return 0
        val take = (n * fraction).toInt().coerceAtLeast(1)
        var s = 0L
        for (i in 0 until take) s += desc[i]
        return ((s * 100) / totalStars).toInt()
    }

    return EcosystemStats(
        years = years,
        cumulativeByYear = cumulative,
        licensedPctByYear = licensedPct,
        activePctByYear = activePct,
        activeAbandonedByYear = activeAbandoned,
        topicTrends = topicTrends,
        lorenz = lorenz,
        top1PctStarShare = topShare(0.01),
        top10PctStarShare = topShare(0.10),
    )
}

/**
 * Fetch a gzip-compressed text resource and decompress it in the browser via the Streams API's
 * `DecompressionStream`. Decompressing client-side (rather than relying on the host to send the
 * file with `Content-Encoding: gzip`) makes the ~9 MB feed load identically on GitHub Pages, a
 * plain static host, or the dev server — the committed asset is the `.gz`, never the 25 MB plain TSV.
 */
private suspend fun fetchGzipText(url: String): String {
    val jsString: kotlin.js.JsString = jsFetchGzipText(url).await<kotlin.js.JsString>()
    return jsString.toString()
}

private fun jsFetchGzipText(url: String): kotlin.js.Promise<kotlin.js.JsString> =
    js("fetch(url).then(function(r){ if(!r.ok){ throw new Error('HTTP '+r.status); } return new Response(r.body.pipeThrough(new DecompressionStream('gzip'))).text(); })")
