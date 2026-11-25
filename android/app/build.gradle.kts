plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.jiner.xrealspace"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.jiner.xrealspace"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.8.0"
    }

    // Release key comes from the environment (GitHub secrets on CI, see docs/WIKI.md "Phát hành").
    // Without it the release APK is signed with the debug key: installable, but not the official signature.
    val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "xreal-space"
            keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8: drops unused library code -> smaller APK
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Vosk: free offline speech recognition (Apache 2.0) — Vietnamese model downloaded at runtime
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
}
