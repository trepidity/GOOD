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
    }
}

rootProject.name = "GOOD"

include(":app-phone")
include(":app-wear")
include(":core:model")
include(":core:wake")
include(":core:sync")
include(":core:ring")
include(":core:lcd")
include(":core:sleep")
// Phone storage (Room) lives in :app-phone under data/.
