plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "no.enaasen.tvremote.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "no.enaasen.tvremote.companion"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
