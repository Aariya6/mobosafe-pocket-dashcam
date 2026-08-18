plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// If JitPack fails to resolve 2.8.0, change this ONE line to "2.7.3" or "2.6.7" and re-sync.
val rootEncoder = "2.6.7"

android {
    namespace = "com.movozen.dashcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.movozen.dashcam"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:$rootEncoder")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
