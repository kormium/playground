buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin Gradle plugin for every module (they apply kotlin("multiplatform") without a version).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        // Compose Multiplatform for the wasmJs demos: the Compose gradle plugin (libs/DSL) plus the
        // Kotlin Compose compiler plugin (versioned with Kotlin). Modules apply both without a version.
        classpath("org.jetbrains.compose:compose-gradle-plugin:1.11.1")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
