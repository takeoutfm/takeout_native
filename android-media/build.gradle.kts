plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Android playback shared by the Takeout apps: Media3 player setup, the background music
// service and its controller, play reporting from the player, and video track ranking.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.takeoutfm.media"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":shared"))
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.guava)
    testImplementation(libs.junit4)
}
