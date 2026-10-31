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
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Private app for one TV: sideloaded, never published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
