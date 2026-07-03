package io.github.kormium.sample.sqldemo

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table

/** The catalog tag tying [Orders] to a database handle. */
object SalesCatalog : Catalog

/** One order row. Typed column delegates — no annotations, no reflection. */
class Order : Entity() {
    var id by Orders.id
    var customer by Orders.customer
    var product by Orders.product
    var category by Orders.category
    var country by Orders.country
    var amount by Orders.amount
    var month by Orders.month
    var date by Orders.date
}

/**
 * A realistic-looking order schema — rich enough that the rows read like real records. The same
 * typed DSL the JVM/Native backends use, here running on wa-sqlite (SQLite compiled to WebAssembly).
 */
object Orders : Table<SalesCatalog, Order>("orders", ::Order) {
    val id by Column.Long().primaryKey()
    val customer by Column.Text()
    val product by Column.Text()
    val category by Column.Text()
    val country by Column.Text()
    val amount by Column.Int() // in cents
    val month by Column.Int() // 1..12, for the monthly chart
    val date by Column.Text() // ISO yyyy-MM-dd

    init { id; customer; product; category; country; amount; month; date }
}

val ordersDdl = """
    CREATE TABLE IF NOT EXISTS "orders" (
        "id" integer NOT NULL,
        "customer" text NOT NULL,
        "product" text NOT NULL,
        "category" text NOT NULL,
        "country" text NOT NULL,
        "amount" integer NOT NULL,
        "month" integer NOT NULL,
        "date" text NOT NULL,
        PRIMARY KEY ("id")
    )
""".trimIndent()

/** Indexes built once after the bulk load, one per dashboard dimension + the amount filter. */
val ordersIndexes = listOf(
    """CREATE INDEX IF NOT EXISTS "orders_amount" ON "orders" ("amount")""",
    """CREATE INDEX IF NOT EXISTS "orders_category_amount" ON "orders" ("category", "amount")""",
    """CREATE INDEX IF NOT EXISTS "orders_country_amount" ON "orders" ("country", "amount")""",
    """CREATE INDEX IF NOT EXISTS "orders_month_amount" ON "orders" ("month", "amount")""",
)

val ordersDropIndexes = listOf(
    """DROP INDEX IF EXISTS "orders_amount"""",
    """DROP INDEX IF EXISTS "orders_category_amount"""",
    """DROP INDEX IF EXISTS "orders_country_amount"""",
    """DROP INDEX IF EXISTS "orders_month_amount"""",
)

/** Zero-pads to two digits (Kotlin/Wasm has no String.format). */
internal fun pad2(n: Int): String = if (n < 10) "0$n" else "$n"
