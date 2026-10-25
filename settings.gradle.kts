pluginManagement {
    includeBuild("build-logic")
    plugins {
        id("org.jetbrains.kotlin.android") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    }
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
rootProject.name = "uit-mobile"
include(":core:model", ":core:common", ":core:domain", ":core:network:common", ":core:network:moodle", ":core:network:daa")
if (!providers.gradleProperty("headless").isPresent) {
    include(":app", ":core:auth", ":core:database", ":core:data", ":core:designsystem")
}
