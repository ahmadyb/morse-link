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

rootProject.name = "Morselink"

include(":app")
include(":core:core-ui")
include(":core:core-data")
include(":core:core-media")
include(":core:core-transfer")
include(":core:core-network")
include(":feature:feature-dashboard")
include(":feature:feature-send")
include(":feature:feature-receive")
include(":feature:feature-transfer-ui")
include(":feature:feature-filemanager")
include(":feature:feature-webshare")
include(":feature:feature-settings")
