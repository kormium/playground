package io.github.kormium.sample.sqldemo.github

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Table

/** The catalog tag for the Kotlin-ecosystem dataset (GitHub repos, crawled on JVM, queried in wasm). */
object KotlinEco : Catalog

/** One GitHub repository whose primary language is Kotlin. */
class Repo : Entity() {
    var id by Repos.id
    var owner by Repos.owner
    var name by Repos.name
    var stars by Repos.stars
    var forks by Repos.forks
    var openIssues by Repos.openIssues
    var sizeKb by Repos.sizeKb
    var createdAt by Repos.createdAt
    var createdYear by Repos.createdYear
    var pushedAt by Repos.pushedAt
    var license by Repos.license
    var archived by Repos.archived
    var topics by Repos.topics
}

/**
 * The `repos` table is shared between platforms: the JVM crawler writes it through Kormium's
 * SQLite driver, the wasm dashboard reads it through kormium-sqlite-wasm — one schema definition,
 * two runtimes. `createdYear` is materialized (not derived from `createdAt`) so the dashboard's
 * GROUP BY axis stays a cheap integer.
 */
object Repos : Table<KotlinEco, Repo>("repos", ::Repo) {
    val id by Column.Long().primaryKey()
    val owner by Column.Text()
    val name by Column.Text()
    val stars by Column.Int()
    val forks by Column.Int()
    val openIssues by Column.Int()
    val sizeKb by Column.Int()
    val createdAt by Column.Text() // ISO yyyy-MM-dd
    val createdYear by Column.Int()
    val pushedAt by Column.Text() // ISO yyyy-MM-dd, "" when GitHub reports null
    val license by Column.Text() // SPDX id, "" when none
    val archived by Column.Int() // 0/1
    val topics by Column.Text() // comma-joined GitHub topics

    init { id; owner; name; stars; forks; openIssues; sizeKb; createdAt; createdYear; pushedAt; license; archived; topics }
}

val reposDdl = """
    CREATE TABLE IF NOT EXISTS "repos" (
        "id" integer NOT NULL,
        "owner" text NOT NULL,
        "name" text NOT NULL,
        "stars" integer NOT NULL,
        "forks" integer NOT NULL,
        "openIssues" integer NOT NULL,
        "sizeKb" integer NOT NULL,
        "createdAt" text NOT NULL,
        "createdYear" integer NOT NULL,
        "pushedAt" text NOT NULL,
        "license" text NOT NULL,
        "archived" integer NOT NULL,
        "topics" text NOT NULL,
        PRIMARY KEY ("id")
    )
""".trimIndent()
