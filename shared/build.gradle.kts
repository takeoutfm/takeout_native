plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The Takeout core shared by every app: API models and the rules that must behave the same on
// all platforms. Android-only for now; Apple targets are added in the Apple phase (PLAN.md).
// commonMain must not import Android or java.* APIs.
kotlin {
    jvmToolchain(17)

    androidTarget()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.security.crypto)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
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
