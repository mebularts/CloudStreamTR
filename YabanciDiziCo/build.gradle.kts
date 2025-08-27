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
    // mağaza/metaveri
    language = "tr"
    description = "yabancidizi.so kaynağı – diziler/filmler, sezon/bölüm ve m3u8/embed yakalama"
    authors = listOf("mebularts")
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://yabancidizi.so/favicon.ico"
    // status = 1 // (opsiyonel) 1: working, 2: beta, vs. Cloudstream sürümüne göre değişebilir
}
