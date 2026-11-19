plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.dzmerchant.cockpit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dzmerchant.cockpit"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("com.google.firebase:firebase-messaging:24.0.0")
    implementation("androidx.core:core-ktx:1.12.0")
}
