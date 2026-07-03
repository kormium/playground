@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kormium.sample.sqldemo

import io.github.kormium.AscDescOrder
import io.github.kormium.Column
import io.github.kormium.Expression
import io.github.kormium.Query
import io.github.kormium.Selectable
import io.github.kormium.and
import io.github.kormium.avg
import io.github.kormium.count
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.gtEq
import io.github.kormium.query
import io.github.kormium.sqlite.wasm.createSqliteWasmDatabase
import io.github.kormium.sum
import io.github.kormium.suspendAutocommit
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

/** The aggregate dashboard for the current filter, plus the total time all its queries took. */
data class Dashboard(
    val count: Long,
    val revenue: Long,
    val avg: Double,
    val byCategory: Map<String, Long>,
    val byCountry: Map<String, Long>,
    val byMonth: Map<Int, Long>,
    val queryMs: Long,
)

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
 * Thin data layer over the Kormium DSL, backed by an in-memory SQLite (wa-sqlite). Every method is
 * the same suspend API a JVM or Native app would call; the database just happens to live in the tab.
 */
class SalesRepository private constructor(private val db: SuspendDatabase<SalesCatalog>) {

    /** Build the combined WHERE for the current filters. */
    private fun filter(minAmount: Int, category: String?, country: String?): Expression {
        var e: Expression = Orders.amount gtEq minAmount
        if (category != null) e = e and (Orders.category eq category)
        if (country != null) e = e and (Orders.country eq country)
        return e
    }

    /**
     * Insert [count] realistic random orders in batches, index afterwards. [onProgress] reports the
     * running row count; a macrotask yield between batches lets the browser paint. Returns ms.
     */
    suspend fun generate(count: Int, batchSize: Int = 4_000, onProgress: (Int) -> Unit = {}): Long {
        val mark = TimeSource.Monotonic.markNow()
        db.suspendTransaction {
            ordersDropIndexes.forEach { Orders.execSql(it) }
            Orders.execSql("""DELETE FROM "orders"""")
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
            ordersIndexes.forEach { Orders.execSql(it) }
            Orders.execSql("ANALYZE")
        }
        return mark.elapsedNow().inWholeMilliseconds
    }

    /** Run every dashboard query for the current filter and time the whole set. */
    suspend fun dashboard(minAmount: Int, category: String?, country: String?): Dashboard {
        val expr = filter(minAmount, category, country)
        val cnt = count()
        val revSum = Orders.amount.sum()
        val avgAmt = Orders.amount.avg()
        val mark = TimeSource.Monotonic.markNow()

        val kpi = db.suspendAutocommit {
            Orders.query().where(expr).select(cnt, revSum, avgAmt)
        }.single()

        val byCategory = db.suspendAutocommit {
            Orders.query().where(expr).groupBy(Orders.category).select(Orders.category, revSum)
        }.associate { it[Orders.category] to (it.getOrNull(revSum) ?: 0L) }

        val byCountry = db.suspendAutocommit {
            Orders.query().where(expr).groupBy(Orders.country).select(Orders.country, cnt)
        }.associate { it[Orders.country] to it[cnt] }

        val byMonth = db.suspendAutocommit {
            Orders.query().where(expr).groupBy(Orders.month).select(Orders.month, revSum)
        }.associate { it[Orders.month] to (it.getOrNull(revSum) ?: 0L) }

        return Dashboard(
            count = kpi[cnt],
            revenue = kpi.getOrNull(revSum) ?: 0L,
            avg = kpi.getOrNull(avgAmt) ?: 0.0,
            byCategory = byCategory,
            byCountry = byCountry,
            byMonth = byMonth,
            queryMs = mark.elapsedNow().inWholeMilliseconds,
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
    ): List<OrderRow> =
        db.suspendAutocommit {
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

    companion object {
        suspend fun open(): SalesRepository {
            val db: SuspendDatabase<SalesCatalog> = createSqliteWasmDatabase() // in-memory :memory:
            db.suspendTransaction { Orders.execSql(ordersDdl) }
            return SalesRepository(db)
        }
    }
}
