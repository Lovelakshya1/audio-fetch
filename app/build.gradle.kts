plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}
android {
    namespace = "com.audiofetch"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.audiofetch"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "10"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    // --- NEW: signing config for release builds ---
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }
    // --- END NEW ---
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release") // NEW
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}
// Chaquopy 15+ uses a top-level chaquopy {} block, not python {} inside defaultConfig
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("yt-dlp==2026.06.09")
            install("mutagen")
            install("Pillow")
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
}
