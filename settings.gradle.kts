pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        // Cloudstream plugin repositories
        maven(url = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/repo")
        maven(url = "https://repo.recloudstream.org") // yedek
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/repo")
        maven(url = "https://repo.recloudstream.org")
    }
}

rootProject.name = "cloudstream-dizipal"
include(":DiziPal")
