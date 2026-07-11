@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sample.sqldemo.crawler

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.sample.sqldemo.github.KotlinEco
import io.github.kormium.sample.sqldemo.github.Repo
import io.github.kormium.sample.sqldemo.github.Repos
import io.github.kormium.sample.sqldemo.github.reposDdl
import io.github.kormium.transaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Crawls every GitHub repository with `language:kotlin` (above a star threshold) into the same
 * SQLite file the wasm dashboard reads — through the same Kormium `Repos` table definition.
 *
 * GitHub's Search API caps any one query at 1000 results, so the crawl shards by `created:`
 * date windows (month seeds, recursively bisected while a window still holds >1000 repos) and
 * paces itself under the 30 searches/minute authenticated limit. Finished months are recorded
 * in `crawl_windows` inside the same database, in the same transaction as their rows, so the
 * crawl can be interrupted and resumed without duplicates or gaps.
 */

// ---------- resumability: crawl bookkeeping, stored next to the data ----------

class CrawlWindow : Entity() {
    var id by CrawlWindows.id
    var repoCount by CrawlWindows.repoCount
}

object CrawlWindows : Table<KotlinEco, CrawlWindow>("crawl_windows", ::CrawlWindow) {
    val id by Column.Text().primaryKey() // "2017-03" month id
    val repoCount by Column.Int()

    init { id; repoCount }
}

private val crawlWindowsDdl = """
    CREATE TABLE IF NOT EXISTS "crawl_windows" (
        "id" text NOT NULL,
        "repoCount" integer NOT NULL,
        PRIMARY KEY ("id")
    )
""".trimIndent()

// ---------- GitHub Search API client ----------

private val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

private class GithubSearch(private val token: String, private val minStars: Int) {
    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    var requests: Int = 0
        private set

    private fun query(from: LocalDateTime, to: LocalDateTime): String =
        "language:kotlin stars:>=$minStars created:${ISO_SECONDS.format(from)}Z..${ISO_SECONDS.format(to)}Z"

    /** One Search API page, with pacing, rate-limit waits and transient-error retries. */
    suspend fun page(from: LocalDateTime, to: LocalDateTime, page: Int): JsonObject {
        val q = URLEncoder.encode(query(from, to), Charsets.UTF_8)
        val url = "https://api.github.com/search/repositories?q=$q&per_page=100&page=$page"
        var attempt = 0
        while (true) {
            delay(2_100) // stay under 30 search requests / minute
            requests++
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build()
            // A multi-hour crawl will hit transient socket drops (EOF mid-response, resets);
            // they are as retryable as a 5xx.
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                if (attempt >= 5) throw e
                attempt++
                println("  network error (${e.message}), retry $attempt/5…")
                delay(5_000L * attempt)
                continue
            }
            when {
                response.statusCode() == 200 ->
                    return json.parseToJsonElement(response.body()).jsonObject

                // Primary or secondary rate limit: wait for the reset (or Retry-After) and go again.
                response.statusCode() == 403 || response.statusCode() == 429 -> {
                    val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()
                    val reset = response.headers().firstValue("x-ratelimit-reset").orElse(null)?.toLongOrNull()
                    val waitSeconds = retryAfter
                        ?: reset?.let { (it - Instant.now().epochSecond).coerceAtLeast(1) }
                        ?: 60
                    println("  rate-limited (${response.statusCode()}), waiting ${waitSeconds}s…")
                    delay(waitSeconds * 1000 + 2_000)
                }

                response.statusCode() >= 500 && attempt < 3 -> {
                    attempt++
                    println("  HTTP ${response.statusCode()}, retry $attempt/3…")
                    delay(5_000L * attempt)
                }

                else -> error("GitHub API HTTP ${response.statusCode()} for $url\n${response.body().take(500)}")
            }
        }
    }

    /**
     * All repos created in [from]..[to] (inclusive). Windows holding >1000 results are bisected —
     * the Search API never returns past the first 1000 — down to 1-hour windows at worst.
     */
    suspend fun fetchWindow(from: LocalDateTime, to: LocalDateTime, depth: Int = 0): List<Repo> {
        val first = page(from, to, 1)
        val total = first["total_count"]?.jsonPrimitive?.intOrNull ?: 0
        if (total > 1000 && Duration.between(from, to).toHours() >= 2) {
            val mid = from.plusSeconds(Duration.between(from, to).seconds / 2)
            val indent = "  ".repeat(depth + 1)
            println("$indent window ${from.toLocalDate()}..${to.toLocalDate()} has $total repos — splitting")
            return fetchWindow(from, mid, depth + 1) + fetchWindow(mid.plusSeconds(1), to, depth + 1)
        }
        if (total > 1000) println("  WARNING: 1-hour window $from still has $total repos; keeping first 1000")

        val repos = mutableListOf<Repo>()
        var pageJson = first
        var pageNo = 1
        while (true) {
            val items = pageJson["items"]?.jsonArray ?: break
            items.forEach { repos.add(toRepo(it.jsonObject)) }
            if (repos.size >= total || items.isEmpty() || pageNo >= 10) break
            pageNo++
            pageJson = page(from, to, pageNo)
        }
        return repos
    }

    private fun toRepo(item: JsonObject): Repo {
        fun str(key: String): String = item[key]?.jsonPrimitive?.contentOrNullSafe() ?: ""
        fun int(key: String): Int = item[key]?.jsonPrimitive?.intOrNull ?: 0
        val fullName = str("full_name")
        val created = str("created_at") // 2017-05-17T09:14:53Z
        return Repo().apply {
            id = item["id"]?.jsonPrimitive?.longOrNull ?: error("repo without id: $item")
            owner = fullName.substringBefore('/')
            name = fullName.substringAfter('/')
            stars = int("stargazers_count")
            forks = int("forks_count")
            openIssues = int("open_issues_count")
            sizeKb = int("size")
            createdAt = created.take(10)
            createdYear = created.take(4).toIntOrNull() ?: 0
            pushedAt = str("pushed_at").take(10)
            license = item["license"]?.let { if (it is JsonObject) it["spdx_id"]?.jsonPrimitive?.contentOrNullSafe() else null } ?: ""
            archived = if (item["archived"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0
            topics = item["topics"]?.jsonArray?.joinToString(",") { t -> t.jsonPrimitive.content } ?: ""
        }
    }
}

/** `content` for non-null primitives, null for JSON null — without throwing on either. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

// ---------- crawl driver ----------

private fun token(): String =
    System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        ?: runCatching {
            ProcessBuilder("gh", "auth", "token").start()
                .inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: error("No GitHub token: set GITHUB_TOKEN or log in with `gh auth login`.")

private data class MonthWindow(val id: String, val from: LocalDateTime, val to: LocalDateTime)

private fun monthWindows(fromDate: LocalDate, toDate: LocalDate): List<MonthWindow> {
    val months = mutableListOf<MonthWindow>()
    var cursor = fromDate.withDayOfMonth(1)
    while (!cursor.isAfter(toDate)) {
        val end = minOf(cursor.plusMonths(1).minusDays(1), toDate)
        months.add(
            MonthWindow(
                id = "%04d-%02d".format(cursor.year, cursor.monthValue),
                from = cursor.atStartOfDay(),
                to = end.atTime(23, 59, 59),
            ),
        )
        cursor = cursor.plusMonths(1)
    }
    return months
}

fun main(args: Array<String>) = runBlocking {
    val opts = args.toList().windowed(2, 2, partialWindows = true).associate { it[0] to it.getOrElse(1) { "" } }
    val minStars = opts["--min-stars"]?.toIntOrNull() ?: 1
    val dbPath = opts["--db"] ?: "kotlin-repos.db"

    // `export` reads the crawled db and writes the browser feed; anything else runs a crawl.
    if (args.firstOrNull() == "export") {
        export(dbPath, opts["--out"] ?: "src/wasmJsMain/resources/repos.tsv")
        return@runBlocking
    }

    val fromDate = LocalDate.parse(opts["--from"] ?: "2011-01-01")
    val today = LocalDate.now()

    val db: Database<KotlinEco> = createSqliteDatabase(dbPath)
    db.use {
        db.transaction {
            Repos.execSql(reposDdl)
            CrawlWindows.execSql(crawlWindowsDdl)
        }
        val done = db.autocommit { CrawlWindows.all() }.associate { it.id to it.repoCount }
        val gh = GithubSearch(token(), minStars)
        val months = monthWindows(fromDate, today)
        println("Crawling language:kotlin stars:>=$minStars into $dbPath")
        println("${months.size} month windows, ${done.size} already done (${done.values.sum()} repos)")

        var crawled = 0
        for ((i, m) in months.withIndex()) {
            if (m.id in done) continue
            val repos = gh.fetchWindow(m.from, m.to).associateBy { it.id }.values.toList()
            db.transaction {
                if (repos.isNotEmpty()) Repos.insertAll(repos)
                CrawlWindows.insertAll(listOf(CrawlWindow().apply { id = m.id; repoCount = repos.size }))
            }
            crawled += repos.size
            println("[${i + 1}/${months.size}] ${m.id}: ${repos.size} repos (run total $crawled, ${gh.requests} API requests)")
        }

        val grandTotal = db.autocommit { CrawlWindows.all() }.sumOf { it.repoCount }
        println("Done: $grandTotal repos in $dbPath (${gh.requests} API requests this run)")
    }
}
