plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.markaz"
    compileSdk = 35
    defaultConfig {
        // New package id isolates this install from earlier test builds/signatures.
        applicationId = "com.basemn.markazajhizati.mobile.compat"
        minSdk = 23
        targetSdk = 35
        versionCode = 13
        versionName = "0.8.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Legacy native-library packaging for broader OEM installer compatibility.
    packaging { jniLibs.useLegacyPackaging = true }

    // Release builds must always use the same persistent signing identity.
    // Never silently fall back to a debug or temporary key.
    val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

    if (releaseStoreFile.isNullOrBlank() ||
        releaseStorePassword.isNullOrBlank() ||
        releaseKeyAlias.isNullOrBlank() ||
        releaseKeyPassword.isNullOrBlank()
    ) {
        throw GradleException(
            "Release signing is not configured. Set ANDROID_KEYSTORE_PATH, " +
                "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
        )
    }

    signingConfigs {
        create("stableRelease") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("stableRelease")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
