@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.sample.sqldemo.crawler

import io.github.kormium.autocommit
import io.github.kormium.createSqliteDatabase
import io.github.kormium.database.Database
import io.github.kormium.sample.sqldemo.github.KotlinEco
import io.github.kormium.sample.sqldemo.github.Repos
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Exports the crawled `repos` table to a compact TSV feed the browser can stream in and re-insert.
 *
 * The browser can't open a prebuilt SQLite file directly (opening it needs SQLite, and the wasm
 * driver exposes no deserialize), so the demo ships the *data*, not the database: one tab-separated
 * line per repo, which the wasm side parses and batch-inserts through the same Kormium DSL. GitHub
 * topics are `[a-z0-9-]` and owners/names carry no tabs or newlines, so TSV needs no escaping.
 *
 * Writes both a plain `.tsv` (served over localhost in dev) and a `.tsv.gz` (for static hosts that
 * don't compress on the wire). Reads every row through Kormium — the same one-API story as the crawl.
 */
fun export(dbPath: String, outPath: String) {
    val db: Database<KotlinEco> = createSqliteDatabase(dbPath)
    val rows = db.use { db.autocommit { Repos.all() } }
    val header = "id\towner\tname\tstars\tforks\topenIssues\tsizeKb\tcreatedAt\tcreatedYear\tpushedAt\tlicense\tarchived\ttopics"
    val sb = StringBuilder(rows.size * 96)
    sb.append(header).append('\n')
    for (r in rows) {
        sb.append(r.id).append('\t')
            .append(r.owner).append('\t')
            .append(r.name).append('\t')
            .append(r.stars).append('\t')
            .append(r.forks).append('\t')
            .append(r.openIssues).append('\t')
            .append(r.sizeKb).append('\t')
            .append(r.createdAt).append('\t')
            .append(r.createdYear).append('\t')
            .append(r.pushedAt).append('\t')
            .append(r.license).append('\t')
            .append(r.archived).append('\t')
            .append(r.topics).append('\n')
    }
    val text = sb.toString()
    File(outPath).writeText(text)
    GZIPOutputStream(File("$outPath.gz").outputStream()).use { it.write(text.toByteArray()) }

    val plainMb = File(outPath).length() / 1_048_576.0
    val gzMb = File("$outPath.gz").length() / 1_048_576.0
    println("Exported ${rows.size} repos to $outPath (%.1f MB, %.1f MB gzipped)".format(plainMb, gzMb))
}
