plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.mebularts"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

dependencies {
    // Cloudstream’ın API’si zaten üst projede tanımlı oluyor.
    // Eğer monorepo değil de tek başına derliyorsan (nadiren gerek):
    // implementation("com.github.LagradOst:cloudstream:3.5.0") // örnek
}
