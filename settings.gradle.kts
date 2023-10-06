@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
}

include(
    ":app",
    ":model",
    ":plugin",
)
