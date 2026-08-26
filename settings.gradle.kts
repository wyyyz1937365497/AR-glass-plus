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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu (topjohnwu) — root shell + RootService
        maven("https://jitpack.io")
    }
}

rootProject.name = "AR-glass-plus"
include(":app")

// Gate 2 spatial calibration APKs (4 independent packages, one activity each)
include(":tools:spatial-test-apps:test-window-1")
include(":tools:spatial-test-apps:test-window-2")
include(":tools:spatial-test-apps:test-window-3")
include(":tools:spatial-test-apps:test-window-4")
