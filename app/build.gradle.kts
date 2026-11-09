plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CHANGELOG.md is the single source of truth for the app version: the newest released
// heading ("## [major.minor.patch]", the first one in the file) becomes versionName.
// Without such a heading, or without the file, the version is 0.0.0.
val releasedVersion: List<Int> = providers
    .fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    .asText
    .orNull
    ?.let { Regex("""^## \[(\d+)\.(\d+)\.(\d+)\]""", RegexOption.MULTILINE).find(it) }
    ?.groupValues
    ?.drop(1)
    ?.map(String::toInt)
    ?: listOf(0, 0, 0)
val (versionMajor, versionMinor, versionPatch) = releasedVersion

android {
    namespace = "me.mupu.sleeptalkrecorder"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.mupu.sleeptalkrecorder"
        minSdk = 31
        targetSdk = 36
        versionCode = maxOf(1, versionMajor * 1_000_000 + versionMinor * 1_000 + versionPatch)
        versionName = "$versionMajor.$versionMinor.$versionPatch"
    }

    // Release signing is not set up yet. It will go into signingConfigs { create("release") }
    // here, reading the keystore path and passwords from keystore.properties (never committed).

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
