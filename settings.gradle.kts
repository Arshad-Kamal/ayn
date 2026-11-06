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
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // com.github.TeamNewPipe:nanojson is published via jitpack (vendored extractor dep).
        // Content-filtered to that one module: jitpack builds on demand and must not serve
        // anything else (dependency-scope review finding).
        maven("https://jitpack.io") {
            content {
                includeModule("com.github.TeamNewPipe", "nanojson")
            }
        }
    }
}

rootProject.name = "ayn"

// Type-safe project accessors (projects.core, projects.ui.tv, ...)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app", ":core", ":core:extractor", ":core:potoken", ":core:sabr", ":feature", ":ui:tv", ":ui:mobile")
