plugins {
    id("com.android.application")
}

android {
    namespace = "com.khayali.pulao"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.khayali.pulao"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    // Release signing comes from the environment (CI secrets or your shell) — never from the repo.
    val ksPath = System.getenv("KP_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KP_KEY_ALIAS")
                keyPassword = System.getenv("KP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
