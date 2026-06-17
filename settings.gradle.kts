pluginManagement {
    repositories {
        google()
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

rootProject.name = "QaLensComposeOverlay"
include(":sample-app")
include(":qalens-core")
include(":qalens-android")
include(":qalens-compose")
include(":qalens-navigation-compose")
include(":qalens-noop")
include(":qalens-replay")
