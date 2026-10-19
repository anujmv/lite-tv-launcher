plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lite.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lite.launcher"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Release key comes from ~/.gradle/gradle.properties; without it, release builds use the debug key.
    val storeFile = providers.gradleProperty("liteStoreFile").orNull
    signingConfigs {
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("liteStorePassword").get()
                keyAlias = providers.gradleProperty("liteKeyAlias").get()
                keyPassword = providers.gradleProperty("liteKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (storeFile != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
