import java.util.Properties

// OpenEmux Mobile: a native Android frontend (Kotlin + Compose) on top of
// the RetroArch engine. The RetroArch Java glue and the prebuilt native
// library come straight from the phoenix project next door; only the
// launcher and the in-game menu are ours.
plugins {
    id("com.android.application") version "9.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

val retroarchAndroid = file("..")

// ScreenScraper developer credential, from the untracked local.properties.
val localProps = Properties().apply {
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(key: String) = "\"" + localProps.getProperty(key, "") + "\""

val openemuxVersion = providers.gradleProperty("openemuxVersion").get()

// Release signing: a properties file outside the repository (storeFile, storePassword,
// keyAlias, keyPassword), named by openemux.signing in local.properties or
// ~/.gradle/gradle.properties. Without it, release builds fall back to the debug key.
val signingProps = (localProps.getProperty("openemux.signing") ?: providers.gradleProperty("openemux.signing").orNull)
    ?.let { file(it) }?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    // The RetroArch Java sources import com.retroarch.R / BuildConfig.
    namespace = "com.retroarch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openemux.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = openemuxVersion.split('.').map(String::toInt).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
        versionName = openemuxVersion
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField("boolean", "PLAY_STORE_BUILD", "false")
        buildConfigField("boolean", "META_QUEST_BUILD", "false")
        buildConfigField("String", "SS_DEVID", localProp("screenscraper.devid"))
        buildConfigField("String", "SS_DEVPASSWORD", localProp("screenscraper.devpassword"))
        resValue("string", "app_name", "OpenEmux")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // libretroarch-activity.so, built with ndk-build in phoenix-common.
            // The libretro cores are not shipped: the first-run setup downloads them.
            jniLibs.directories += listOf(
                "$retroarchAndroid/phoenix-common/libs",
            )
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // The JVM tests call android.util.Log; the stub answers instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (signingProps != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("openemux.signing is not set: the release APK is signed with the debug key.")
            }
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
    implementation("androidx.window:window:1.4.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM: the archive tests need the real one.
    testImplementation("org.json:json:20240303")
    // Share nearby: P-256 test certificates for the loopback TLS tests (test-only, MIT).
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    // Share nearby: the instrumented discovery test reads the multicast lock on a device.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
