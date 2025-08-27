pluginManagement {
    repositories {
        // SIRASI ÖNEMLİ DEĞİL ama google() mutlaka olmalı
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io")
        // Cloudstream gradle plugin'i için:
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
include(":Dizipal") // modül adın neyse birebir aynı!
