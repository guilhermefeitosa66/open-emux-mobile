// OpenEmux Mobile: a native Android frontend (Kotlin + Compose) on top of
// the RetroArch engine. The RetroArch Java glue and the prebuilt native
// library come straight from the phoenix project next door; only the
// launcher and the in-game menu are ours.
plugins {
    id("com.android.application") version "9.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

val retroarchAndroid = file("..")

android {
    // The RetroArch Java sources import com.retroarch.R / BuildConfig.
    namespace = "com.retroarch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openemux.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("boolean", "PLAY_STORE_BUILD", "false")
        buildConfigField("boolean", "META_QUEST_BUILD", "false")
        resValue("string", "app_name", "OpenEmux")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    sourceSets {
        getByName("main") {
            java.directories += listOf(
                "$retroarchAndroid/phoenix/src",
                "$retroarchAndroid/phoenix-common/src",
                "$retroarchAndroid/play-core-stub",
                "$retroarchAndroid/../../libretro-common/vfs/saf/src",
            )
            res.directories += listOf(
                "$retroarchAndroid/phoenix/res",
            )
            // libretroarch-activity.so, built with ndk-build in phoenix-common,
            // plus the libretro cores we ship for GBA and SNES.
            jniLibs.directories += listOf(
                "$retroarchAndroid/phoenix-common/libs",
                "corelibs",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
