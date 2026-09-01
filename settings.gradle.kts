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

rootProject.name = "pump-link"

// :protocol, :domain, :presentation, and :simulator are plain JVM modules.
// That is a constraint, not an accident: it is what keeps the scenario suite
// and the MVI exhaustiveness rule runnable in CI without a device. See
// docs/07-architecture.md.
include(":app")
include(":data")
include(":domain")
include(":presentation")
include(":protocol")
include(":simulator")
