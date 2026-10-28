plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// The Takeout core shared by every app: API models and the rules that must behave the same on
// all platforms. Android, and macOS and iOS for the SwiftUI apps in apple/ (PLAN.md phase 10).
// commonMain must not import Android or java.* APIs; Apple pieces go in appleMain.
kotlin {
    jvmToolchain(17)

    androidTarget()

    // The Mac and iOS apps' framework ("Shared"), linked on a Mac (apple/README.md). Its klibs
    // also compile on Linux (kotlin.native.enableKlibsCrossCompilation), which checks appleMain
    // here.
    listOf(macosArm64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization)
            api(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.security.crypto)
            implementation(libs.sqldelight.android.driver)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

android {
    namespace = "com.takeoutfm.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
    }
}

// The on-device database: offline progress and listen queues, Recently Played.
sqldelight {
    databases {
        create("TakeoutDb") {
            packageName.set("com.takeoutfm.shared.db")
        }
    }
}
