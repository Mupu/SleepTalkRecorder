import java.io.StringReader
import java.util.Properties

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

// The release key never enters the repository. Each signing value comes from keystore.properties
// at the repository root (ignored by Git) or, when the file lacks it, from an environment
// variable. Without all four values the release build stays unsigned, so debug builds and CI
// work without any secrets.
val keystoreProperties = Properties().apply {
    providers
        .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText
        .orNull
        ?.let { load(StringReader(it)) }
}
val releaseSigningValues: Map<String, String?> = mapOf(
    "storeFile" to "RELEASE_STORE_FILE",
    "storePassword" to "RELEASE_STORE_PASSWORD",
    "keyAlias" to "RELEASE_KEY_ALIAS",
    "keyPassword" to "RELEASE_KEY_PASSWORD",
).mapValues { (property, variable) ->
    keystoreProperties.getProperty(property)?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable(variable).orNull?.takeIf(String::isNotBlank)
}
val missingReleaseSigningValues = releaseSigningValues.filterValues { it == null }.keys

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

    signingConfigs {
        if (missingReleaseSigningValues.isEmpty()) {
            create("release") {
                // A relative path is resolved against the repository root.
                storeFile = rootProject.file(releaseSigningValues.getValue("storeFile")!!)
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

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

// An unsigned release APK cannot be installed, so say so after every release build without a key.
if (missingReleaseSigningValues.isNotEmpty()) {
    val warning = "The release build is UNSIGNED and cannot be installed: " +
        "${missingReleaseSigningValues.joinToString()} missing from keystore.properties and the " +
        "RELEASE_* environment variables. See \"Installing on a phone\" in README.md."
    tasks.named { it == "assembleRelease" || it == "bundleRelease" }.configureEach {
        doLast { logger.warn(warning) }
    }
}
