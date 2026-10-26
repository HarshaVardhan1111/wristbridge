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

rootProject.name = "WristBridge"

include(":app")

// Pure-Kotlin protocol logic. An included build, so it builds and tests
// without the Android SDK: ./gradlew -p protocol test
includeBuild("protocol")
