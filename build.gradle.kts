plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.foldblur"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.foldblur"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
