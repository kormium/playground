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

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "demo.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation("io.github.kormium:kormium-core:0.10.0")
                implementation("io.github.kormium:kormium-sqlite-wasm:0.10.0")

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")

                implementation("com.himanshoe:charty:3.0.0-rc01")
            }
        }
    }
}
