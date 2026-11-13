// Standalone build for the Kotlin Multiplatform core shared by NuvioTV (Android) and the tvOS app.
// Build it from the repo root with: ./gradlew -p shared <task>
// The Android app can later consume it as a composite build: includeBuild("shared").
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        // Share dependency versions with the Android app.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "nuvio-shared"
