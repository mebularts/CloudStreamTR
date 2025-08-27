pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Cloudstream Gradle plugin
        maven("https://raw.githubusercontent.com/recloudstream/cloudstream/master/repo")
        maven("https://repo.recloudstream.org")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://raw.githubusercontent.com/recloudstream/cloudstream/master/repo")
        maven("https://repo.recloudstream.org")
    }
}

rootProject.name = "CloudStreamTR"
include(":Dizipal")
