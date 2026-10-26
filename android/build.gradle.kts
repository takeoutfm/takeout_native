import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

/**
 * The release key, from `android/key.properties` (not committed; the same keys as the Flutter
 * app's `key.properties`: storeFile, storePassword, keyAlias, keyPassword). Without it,
 * release builds are signed with the debug key and can't replace the Play Store app.
 */
val releaseKey = Properties().apply {
    val file = project.file("key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.takeoutfm.mobile"
    compileSdk = 36

    defaultConfig {
        // Replaces the Flutter app in place (same id; higher versionCode than its 2600).
        applicationId = "com.takeoutfm.mobile"
        minSdk = 28
        // The Flutter app it replaces already targets 36.
        targetSdk = 36
        versionCode = 3000
        versionName = "3.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKey.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile")).takeIf { it.exists() }
                    ?: project.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Installs beside the Flutter app during development.
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":android-media"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization)
    implementation(libs.coil.compose)
    // Installs the libraries' baseline profiles (Compose, Media3…) on sideloaded APKs too, so
    // startup and scrolling are compiled ahead of time from the first launch.
    implementation(libs.androidx.profileinstaller)
    // PlayerView: the video player's controls, subtitles and track/speed settings
    implementation(libs.androidx.media3.ui.views)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
