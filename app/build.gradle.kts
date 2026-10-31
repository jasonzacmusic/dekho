plugins {
    id("com.android.application")
}

android {
    namespace = "com.jasonzac.dekho"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jasonzac.dekho"
        minSdk = 28
        targetSdk = 35
        versionCode = 12
        versionName = "1.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded from GitHub, never on the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
