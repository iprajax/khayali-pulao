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
        versionCode = 9
        versionName = "0.8.0"
        // LiteRT-LM ships arm64 only; every modern Android phone is arm64.
        ndk { abiFilters += "arm64-v8a" }
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
    // On-device LLM runtime (Gemma 4 E2B). Runs locally; the model file is downloaded once from Hugging Face.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("junit:junit:4.13.2")
}

// The live market test loads years of real NSE data into memory.
tasks.withType<Test>().configureEach { maxHeapSize = "3g" }
