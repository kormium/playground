plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    google()
    mavenCentral()
}

// Same kotlinx-datetime link fix as samples/wasm-todo: 0.6.2's `Instant` typealias double-binds
// against Kotlin 2.4's stable kotlin.time.Instant during the wasm executable link. The
// `-0.6.x-compat` build is ABI-compatible and drops the bridge, so force it on the link classpath.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")
    }
}

// The Compose compiler is only for the wasm UI; the jvm target (the crawler) has no Compose
// runtime on its classpath and must not get the plugin.
composeCompiler {
    targetKotlinPlatforms.set(setOf(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.wasm))
}

kotlin {
    jvmToolchain(21)

    // The GitHub crawler: writes the same `repos` table (commonMain schema) the wasm dashboard
    // reads — Kormium on JVM/SQLite producing the dataset, Kormium on wasm querying it.
    jvm {
        binaries {
            executable {
                mainClass.set("io.github.kormium.sample.sqldemo.crawler.CrawlerKt")
            }
        }
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "demo.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.github.kormium:kormium-core:0.10.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.github.kormium:kormium-sqlite:0.10.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation("io.github.kormium:kormium-sqlite-wasm:0.10.0")

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)

                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")

                implementation("com.himanshoe:charty:3.0.0-rc01")
            }
        }
    }
}
