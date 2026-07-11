@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sample.sqldemo

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
import io.github.kormium.query
import io.github.kormium.sqlite.wasm.createWorkerSqliteWasmDatabase
import io.github.kormium.sum
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.TimeSource

private val CUSTOMERS = listOf(
    "Ada Lovelace", "Alan Turing", "Grace Hopper", "Linus Torvalds", "Margaret Hamilton",
    "Dennis Ritchie", "Barbara Liskov", "Ken Thompson", "Katherine Johnson", "Guido van Rossum",
    "Anita Borg", "Donald Knuth", "Radia Perlman", "James Gosling", "Frances Allen",
)
private val PRODUCTS = listOf(
    "Mechanical Keyboard", "Wireless Mouse", "4K Monitor", "USB-C Hub", "Webcam",
    "Laptop Stand", "Headphones", "Desk Mat", "SSD 1TB", "Docking Station",
)
val CATEGORIES = listOf("Music", "Games", "Books", "Film", "Software", "Hardware")
val COUNTRIES = listOf("US", "DE", "GB", "FR", "JP", "BR", "IN", "CA", "AU", "NL")

/** One row read out of the database, as plain data for the UI table. */
data class OrderRow(
    val id: Long,
    val customer: String,
    val product: String,
    val category: String,
    val country: String,
    val amount: Int,
    val date: String,
)

/** One timed query, for the on-screen query log — the actual SQL text, not just a description. */
data class QueryLogEntry(val label: String, val sql: String, val ms: Long)

/** The rows from [SalesRepository.sampleRows], paired with the log entry for that query. */
data class TimedRows(val rows: List<OrderRow>, val entry: QueryLogEntry)

/** A query result plus the log entry (label, SQL, timing) for the query that produced it. */
data class TimedValue<T>(val value: T, val entry: QueryLogEntry)

/**
 * Everything the dashboard shows, rolled up client-side from ONE grouped query (see
 * [SalesRepository.dashboard]): the GROUP BY axes are tiny (6 categories x 10 countries x
 * 12 months = at most 720 groups), so one scan replaces the three separate full scans the
 * per-chart queries used to cost.
 */
data class Dashboard(
    val byCategory: Map<String, Long>,
    val byCountry: Map<String, Long>,
    val byMonth: Map<Int, Long>,
    val count: Long,
    val revenue: Long,
    val avg: Double,
)

/** [value] plus how long, in ms, producing it took. */
data class Timed<T>(val value: T, val ms: Long)

private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
    val mark = TimeSource.Monotonic.markNow()
    val value = block()
    return Timed(value, mark.elapsedNow().inWholeMilliseconds)
}

/** Sortable table columns, mapped to real Kormium columns inside the repository. */
enum class SortCol { CUSTOMER, PRODUCT, CATEGORY, COUNTRY, AMOUNT, DATE }

private fun SortCol.column(): Column<*, *, *> = when (this) {
    SortCol.CUSTOMER -> Orders.customer
    SortCol.PRODUCT -> Orders.product
    SortCol.CATEGORY -> Orders.category
    SortCol.COUNTRY -> Orders.country
    SortCol.AMOUNT -> Orders.amount
    SortCol.DATE -> Orders.date
}

/**
 * Thin data layer over the Kormium DSL, backed by an in-memory SQLite (see [open] for why
 * in-memory beat the pooled-OPFS alternatives for this workload). Every method is the same
 * suspend API a JVM or Native app would call; the database just happens to live in the tab.
 *
 * The two constructor lanes ([interactive] for the rows table, [analytics] for the full-scan
 * aggregates and bulk writes) are kept from the pooled experiment so the call sites stay
 * lane-aware — with a single connection both point at the same database and the `Mutex`
 * serializes them, which is exactly the fastest configuration measured for this workload.
 */
class SalesRepository private constructor(
    private val interactive: SuspendDatabase<SalesCatalog>,
    private val analytics: SuspendDatabase<SalesCatalog>,
) {

    /** Build the combined WHERE for the current filters. */
    private fun filter(minAmount: Int, category: String?, country: String?): Expression {
        var e: Expression = Orders.amount gtEq minAmount
        if (category != null) e = e and (Orders.category eq category)
        if (country != null) e = e and (Orders.country eq country)
        return e
    }

    /** The same filter, rendered as SQL text for the query log — the log shows what actually ran. */
    private fun filterSql(minAmount: Int, category: String?, country: String?): String {
        val parts = mutableListOf("amount >= $minAmount")
        if (category != null) parts += "category = '$category'"
        if (country != null) parts += "country = '$country'"
        return parts.joinToString(" AND ")
    }

    /**
     * Insert [count] realistic random orders in batches, index afterwards. [onProgress] reports the
     * running row count; [onPhase] reports what the build is doing once row inserts finish (index
     * builds and ANALYZE take seconds at 1M rows — without this the UI looks frozen). A macrotask
     * yield between batches lets the browser paint. Returns ms.
     */
    suspend fun generate(
        count: Int,
        batchSize: Int = 4_000,
        onProgress: (Int) -> Unit = {},
        onPhase: (String) -> Unit = {},
    ): Long {
        val mark = TimeSource.Monotonic.markNow()
        analytics.suspendTransaction {
            onPhase("Clearing previous data…")
            ordersDropIndexes.forEach { Orders.execSql(it) }
            Orders.execSql("""DELETE FROM "orders"""")
            onPhase("Inserting rows…")
            var id = 0L
            while (id < count) {
                val n = minOf(batchSize.toLong(), count - id).toInt()
                val batch = ArrayList<Order>(n)
                repeat(n) {
                    val m = Random.nextInt(1, 13)
                    batch.add(
                        Order().apply {
                            this.id = id++
                            customer = CUSTOMERS[Random.nextInt(CUSTOMERS.size)]
                            product = PRODUCTS[Random.nextInt(PRODUCTS.size)]
                            category = CATEGORIES[Random.nextInt(CATEGORIES.size)]
                            country = COUNTRIES[Random.nextInt(COUNTRIES.size)]
                            amount = Random.nextInt(100, 50_000)
                            month = m
                            date = "2024-${pad2(m)}-${pad2(Random.nextInt(1, 29))}"
                        },
                    )
                }
                Orders.insertAll(batch)
                onProgress(id.toInt())
                delay(1) // macrotask yield so the browser can paint the progress bar
            }
            ordersIndexes.forEachIndexed { i, indexSql ->
                val name = indexSql.substringAfter("EXISTS \"").substringBefore("\"")
                onPhase("Building index ${i + 1}/${ordersIndexes.size}: $name…")
                Orders.execSql(indexSql)
            }
            onPhase("Analyzing (query planner statistics)…")
            Orders.execSql("ANALYZE")
        }
        return mark.elapsedNow().inWholeMilliseconds
    }

    /**
     * The whole dashboard in ONE query. The three charts differ only in their GROUP BY axis, over
     * the same filtered rows — so instead of three full scans, one scan grouped by all three axes
     * (at most 6x10x12 = 720 groups comes back) and the per-chart maps + KPI roll up client-side.
     * Measured: cuts the dashboard's total query time to roughly a third.
     */
    suspend fun dashboard(minAmount: Int, category: String?, country: String?): TimedValue<Dashboard> {
        val expr = filter(minAmount, category, country)
        val revSum = Orders.amount.sum()
        val cnt = count()
        val sql = "SELECT category, country, month, COUNT(*), SUM(amount) FROM orders " +
            "WHERE ${filterSql(minAmount, category, country)} GROUP BY category, country, month"
        val t = timed {
            analytics.suspendTransaction(readOnly = true) {
                Orders.query().where(expr)
                    .groupBy(Orders.category, Orders.country, Orders.month)
                    .select(Orders.category, Orders.country, Orders.month, cnt, revSum)
            }
        }
        val byCategory = mutableMapOf<String, Long>()
        val byCountry = mutableMapOf<String, Long>()
        val byMonth = mutableMapOf<Int, Long>()
        var totalCount = 0L
        var totalRevenue = 0L
        for (row in t.value) {
            val groupCount = row[cnt]
            val groupRevenue = row.getOrNull(revSum) ?: 0L
            byCategory[row[Orders.category]] = (byCategory[row[Orders.category]] ?: 0L) + groupRevenue
            byCountry[row[Orders.country]] = (byCountry[row[Orders.country]] ?: 0L) + groupCount
            byMonth[row[Orders.month]] = (byMonth[row[Orders.month]] ?: 0L) + groupRevenue
            totalCount += groupCount
            totalRevenue += groupRevenue
        }
        val avg = if (totalCount > 0) totalRevenue.toDouble() / totalCount else 0.0
        return TimedValue(
            Dashboard(byCategory, byCountry, byMonth, totalCount, totalRevenue, avg),
            QueryLogEntry("Dashboard (all charts + KPI, one scan)", sql, t.ms),
        )
    }

    /** A live, sorted sample of the matching rows, so the data is visible, not just aggregated. */
    suspend fun sampleRows(
        minAmount: Int,
        category: String?,
        country: String?,
        sort: SortCol,
        ascending: Boolean,
        limit: Int = 100,
    ): TimedRows {
        val direction = if (ascending) "ASC" else "DESC"
        val sql = "SELECT * FROM orders WHERE ${filterSql(minAmount, category, country)} " +
            "ORDER BY ${sort.name.lowercase()} $direction LIMIT $limit"
        val t = timed {
            interactive.suspendTransaction(readOnly = true) {
                Orders.find(
                    Query(
                        whereExpression = filter(minAmount, category, country),
                        orderBy = mapOf<Selectable<*>, AscDescOrder>(
                            sort.column() to if (ascending) AscDescOrder.ASC else AscDescOrder.DESC,
                        ),
                        limit = limit.toUInt(),
                    ),
                )
            }.map {
                OrderRow(it.id, it.customer, it.product, it.category, it.country, it.amount, it.date)
            }
        }
        return TimedRows(t.value, QueryLogEntry("Rows", sql, t.ms))
    }

    companion object {
        // In-memory, single connection, hosted in a Worker — deliberately, after measuring the
        // alternatives. This demo regenerates its dataset on every load, so OPFS persistence buys
        // nothing here, and the pooled OPFS engine (createPooledSqliteWasmDatabase, korm
        // docs/web-targets.md Phase 4) measured strictly worse for this workload: opfs-wl's
        // per-statement lock handoff between connections cost more than reader parallelism won
        // (4-query burst: ~410 ms wall on 1 reader vs ~1030 ms on 4), and under rapid slider
        // bursts multi-connection lock acquisition failed outright (xLock GetSyncHandleError —
        // OPFS sync access handles are exclusive by spec). Memory-speed queries on one connection
        // are the right tool when the data fits and doesn't need to survive a reload — and hosting
        // that connection in a Worker (unlike the original main-thread wa-sqlite engine) keeps the
        // UI rendering while an aggregate runs. Both lanes point at the same connection so the
        // lane-aware call sites stay as they are.
        suspend fun open(): SalesRepository {
            val db: SuspendDatabase<SalesCatalog> = createWorkerSqliteWasmDatabase()
            db.suspendTransaction { Orders.execSql(ordersDdl) }
            return SalesRepository(interactive = db, analytics = db)
        }
    }
}
