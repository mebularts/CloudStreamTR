plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.mebularts"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

cloudstream {
    language = "tr"
    description = "cizgivedizi.com kaynak eklentisi — film ve dizi listeleri, bölüm çıkarma, m3u8/embed yakalama."
    authors = listOf("mebularts")
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.cizgivedizi.com/favicon.ico"
}

dependencies {
    // Ekstra bağımlılık yok; Cloudstream API kök projeden geliyor.
}
