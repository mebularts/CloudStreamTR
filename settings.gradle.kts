pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Cloudstream plugin repo'ları
        maven("https://raw.githubusercontent.com/recloudstream/cloudstream/master/repo")
        maven("https://repo.recloudstream.org")
    }
}

dependencyResolutionManagement {
    // build.gradle dosyalarında repository tanımlanmasın:
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS) // veya FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://raw.githubusercontent.com/recloudstream/cloudstream/master/repo")
        maven("https://repo.recloudstream.org")
    }
}

rootProject.name = "CloudStreamTR"
