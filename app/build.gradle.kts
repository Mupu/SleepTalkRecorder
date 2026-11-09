import java.io.File
import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
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

/**
 * Downloads [url] once into [target] and fails the build unless the file has the SHA-256 [sha256].
 * A file already there with the right checksum is kept, so only the first build downloads.
 */
abstract class VerifiedDownload : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun download() {
        val file = target.get().asFile
        if (file.isFile && sha256Of(file) == sha256.get()) return

        file.parentFile.mkdirs()
        // A file of its own per download, so parallel builds never write into the same one.
        val partial = File.createTempFile(file.name, ".part", file.parentFile)
        try {
            URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha256Of(partial)
            if (actual != sha256.get()) {
                throw GradleException(
                    "Checksum mismatch for ${url.get()}: expected SHA-256 ${sha256.get()}, got $actual. " +
                        "The release asset changed; check it and update the pinned checksum in app/build.gradle.kts.",
                )
            }
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Copies the files in [folder] of the JAR [archive] into [target], without their folders. */
abstract class UnpackNativeLibraries : DefaultTask() {
    @get:InputFile
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val target: DirectoryProperty

    @TaskAction
    fun unpack() {
        val directory = target.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        ZipFile(archive.get().asFile).use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(folder.get())) continue
                jar.getInputStream(entry).use { input ->
                    File(directory, entry.name.substringAfterLast('/')).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}

// sherpa-onnx, the speech recognition runtime, is published only as an AAR on its GitHub releases
// (no Maven artifact). The build downloads the pinned release once into the Gradle user home,
// which CI caches too, and checks it against the checksum below, so the AAR never enters Git.
val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val downloadSherpaOnnx = tasks.register<VerifiedDownload>("downloadSherpaOnnx") {
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    sha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
    target = gradle.gradleUserHomeDir.resolve("caches/sleeptalkrecorder/sherpa-onnx-$sherpaOnnxVersion.aar")
}

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

    // sherpa-onnx brings its native libraries for four ABIs, about 30 MB each. Phones the app
    // targets are arm64; debug builds also keep x86_64 for the emulator.
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    implementation(files(downloadSherpaOnnx.flatMap { it.target }))
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

// The transcription evaluation on the local test nights (TranscriptionEvaluationTest) runs only
// when the Gradle property transcriptionEvaluation names the test data directory and
// transcriptionModel the directory of the Parakeet files; see README.md. On the computer it uses
// sherpa-onnx's desktop library of the same release, downloaded and checked like the AAR.
val transcriptionEvaluationData: String? = providers.gradleProperty("transcriptionEvaluation").orNull
    ?.let { rootProject.file(it).absolutePath }
if (transcriptionEvaluationData != null) {
    val modelDirectory = providers.gradleProperty("transcriptionModel").orNull
        ?.let { rootProject.file(it).absolutePath }
        ?: throw GradleException("transcriptionEvaluation needs transcriptionModel, the directory of the Parakeet model files")

    val osName = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    val platform = when {
        osName.startsWith("windows") -> if (arm) "win-arm64" else "win-x64"
        osName.startsWith("mac") -> if (arm) "osx-aarch64" else "osx-x64"
        else -> if (arm) "linux-aarch64" else "linux-x64"
    }
    // SHA-256 of sherpa-onnx-native-lib-<platform>-<version>.jar of the pinned release.
    val nativeLibraryChecksums = mapOf(
        "win-x64" to "536f1c09025996a780b22f972b2e56e0e8b2064e1ceb534456ea7eba0a6ae4cd",
        "win-arm64" to "95a2a4fe4a368fb4d9f458f68fd8ed179f9f2c07bc448185633bc199066a38bb",
        "osx-aarch64" to "718a4ea4799341146ef9a0cc15daa70e6622ae4a16911952604035f396703499",
        "osx-x64" to "617a6263b4793495ea6a5f882cfc5dbf9e57cf4ad1e126104bddf8cf90846f72",
        "linux-x64" to "744330a68a7024d9412f029c23b06f847b8444787c94e5118e0fdaf1b4b2cb3f",
        "linux-aarch64" to "c09d51e7190f5b8bcc20cf0d25bcb2e66324737ace5ce9d678ae37b8c79d6c2f",
    )
    val downloadDesktopLibrary = tasks.register<VerifiedDownload>("downloadSherpaOnnxDesktop") {
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/" +
            "sherpa-onnx-native-lib-$platform-$sherpaOnnxVersion.jar"
        sha256 = nativeLibraryChecksums.getValue(platform)
        target = gradle.gradleUserHomeDir.resolve("caches/sleeptalkrecorder/sherpa-onnx-native-lib-$platform-$sherpaOnnxVersion.jar")
    }
    val desktopLibrary = layout.buildDirectory.dir("sherpa-onnx-desktop")
    val extractDesktopLibrary = tasks.register<UnpackNativeLibraries>("extractSherpaOnnxDesktop") {
        archive = downloadDesktopLibrary.flatMap { it.target }
        folder = "sherpa-onnx/native/$platform/"
        target = desktopLibrary
    }

    tasks.withType<Test>().configureEach {
        dependsOn(extractDesktopLibrary)
        val libraries = desktopLibrary.get().asFile.absolutePath
        systemProperty("transcriptionEvaluation.testdata", transcriptionEvaluationData)
        systemProperty("transcriptionEvaluation.model", modelDirectory)
        systemProperty("transcriptionEvaluation.output", rootProject.file(".local/test-output/transcription").absolutePath)
        systemProperty("detectorEvaluation.output", detectorEvaluationOutput)
        systemProperty("transcriptionEvaluation.libraries", libraries)
        systemProperty("java.library.path", libraries)
        // The JNI library finds the ONNX Runtime library beside it only through the search path.
        environment("PATH", libraries + File.pathSeparator + System.getenv("PATH").orEmpty())
        environment("LD_LIBRARY_PATH", libraries)
        environment("DYLD_LIBRARY_PATH", libraries)
        maxHeapSize = "4g"
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
