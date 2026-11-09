pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

// The Kotlin plugin keeps its project data under .local/kotlin (gradle.properties), but expects the
// folder to exist; a fresh clone has no .local/ yet, so create it before any compilation starts.
rootDir.resolve(".local/kotlin/sessions").mkdirs()

// Build output stays under the ignored .local/ with the other machine-local files:
// .local/build/root for the root project, .local/build/<name> for each module.
gradle.lifecycle.beforeProject {
    layout.buildDirectory.set(rootDir.resolve(".local/build/${if (path == ":") "root" else name}"))
}

rootProject.name = "SleepTalkRecorder"
include(":app")
