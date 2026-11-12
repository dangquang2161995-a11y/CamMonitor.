plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dq.cammonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dq.cammonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
    // Thư viện đọc camera UVC qua USB (không cần root)
    implementation("com.herohan:UVCAndroid:1.0.13")
}
