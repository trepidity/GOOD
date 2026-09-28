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
// Added in later milestones: :core:data (Room, M1), :core:sleep (Health Connect + Sleep API, M3)
