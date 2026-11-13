import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.nuvio"
version = "0.1.0"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    val xcFramework = XCFramework("NuvioShared")
    listOf(
        tvosArm64(),
        tvosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "NuvioShared"
            isStatic = false
            xcFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
