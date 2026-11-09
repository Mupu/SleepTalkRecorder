import java.io.StringReader
import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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

    // Lists the translated languages for the system's per-app language setting (Android 13+);
    // res/resources.properties names the language of the untranslated resources.
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

// Exported schemas are tracked so that, from the first real release on, every schema change can
// be checked against them and get a Room migration.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The detector evaluation on the local test nights (DetectorEvaluationTest) runs only when the
// Gradle property detectorEvaluation names the test data directory; see README.md. It then always
// runs instead of coming from the build cache, and prints its table.
val detectorEvaluationData: String? = providers.gradleProperty("detectorEvaluation").orNull
    ?.let { rootProject.file(it).absolutePath }
val detectorEvaluationOutput: String = rootProject.file(".local/test-output/detector").absolutePath
tasks.withType<Test>().configureEach {
    if (detectorEvaluationData != null) {
        systemProperty("detectorEvaluation.testdata", detectorEvaluationData)
        systemProperty("detectorEvaluation.output", detectorEvaluationOutput)
        outputs.cacheIf { false }
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
        testLogging.exceptionFormat = TestExceptionFormat.FULL
    }
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
