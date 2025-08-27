pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
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

// 🔽 En azından bunu ekle:
include(":DiziPal")

// (İstersen otomatik tarama da kullanabilirsin)
/*
val disabled = listOf("__Temel")
File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(":${dir.name}")
    }
}
fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
*/
