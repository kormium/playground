# Kormium Playground

Small, self-contained demos built on [Kormium](https://github.com/kormium/kormium), a type-safe
Kotlin Multiplatform ORM / SQL DSL. Kept separate from the main repo so each demo can pull in its
own heavy or experimental dependencies (Compose Multiplatform, charting libraries, browser-only
targets) without affecting the core.

## Demos

- [`sql-demo`](sql-demo) — a real SQL analytics dashboard running entirely in the browser, backed
  by an in-browser SQLite (wa-sqlite / Kotlin-Wasm) and Kormium's type-safe DSL. No server.

## Building

Each demo depends only on published Kormium artifacts from Maven Central — no composite build, no
sibling checkout required.

```bash
git clone git@github.com:kormium/playground.git
cd playground
./gradlew :sql-demo:wasmJsBrowserDevelopmentRun
```

## License

Apache 2.0. See `LICENSE` and `NOTICE`.
