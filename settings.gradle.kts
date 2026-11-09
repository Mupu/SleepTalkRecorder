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

// Build output stays under the ignored .local/ with the other machine-local files:
// .local/build/root for the root project, .local/build/<name> for each module.
gradle.lifecycle.beforeProject {
    layout.buildDirectory.set(rootDir.resolve(".local/build/${if (path == ":") "root" else name}"))
}

rootProject.name = "SleepTalkRecorder"
include(":app")
