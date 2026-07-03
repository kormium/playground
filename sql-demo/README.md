# sql-demo — a real SQL dashboard in the browser, at scale

A Compose Multiplatform (`wasmJs`) demo: an analytics dashboard backed by a genuine **in-browser
SQLite** (wa-sqlite, SQLite compiled to WebAssembly), queried entirely through Kormium's type-safe
Kotlin DSL — no server, no backend, the whole database lives in the tab.

Generate up to a million rows, then filter, sort and re-aggregate them live. Every tile, chart and
table row on screen comes from a real SQL query (`GROUP BY` / `SUM` / `AVG`, filtered `WHERE`,
`ORDER BY`) run against the full dataset, with the query time shown on screen.

## Run

```bash
./gradlew :sql-demo:wasmJsBrowserDevelopmentRun
```

Click **Load 100k / 500k / 1M rows**, then use the amount slider and category/country filters —
each change re-runs the dashboard's queries and the KPI tiles, charts and row table update live.

## What it shows

- **Scale + speed** — load up to 1M rows and query them in-browser, with live timings.
- **A real dashboard, not just a counter** — KPI tiles, a pie chart, a line chart, bar charts and a
  sortable, filterable row table, all driven by SQL.
- **Type-safe Kotlin, not raw SQL** — the query shown on screen is the actual Kormium DSL.
- **The same code runs on a server** — this exact query API targets PostgreSQL/MySQL on JVM/Native.

## Status

Experimental. Depends on Kormium's web/Wasm modules (`kormium-core`, `kormium-sqlite-wasm`),
published from [kormium/kormium](https://github.com/kormium/kormium) starting at 0.9.1.
