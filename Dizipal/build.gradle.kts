version = 13

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.mebularts.dizipal"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    buildFeatures { buildConfig = false }
}

cloudstream {
    authors     = listOf("mebularts", "muratcesmecioglu")
    language    = "tr"
    description = "En yeni dizi/filmleri hızlıca listeler."
    /**
     * 0: Down, 1: Ok, 2: Slow, 3: Beta
     */
    status  = 1
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizipal1103.com&sz=%size%"
}
