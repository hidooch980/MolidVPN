plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreFile.exists()

android {
    namespace = "com.molidvpn"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.molidvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "0.5.6"
        ndk {
            // Psiphon's native libgojni.so ships for x86 too; the phones this app
            // targets are arm/x86_64, so keep the APK to those ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Psiphon's libgojni.so is ~23 MB per ABI; storing it compressed keeps the
    // download near 40 MB instead of 80 MB, which matters on Iranian mobile data.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // lint artifacts are not in the offline Gradle cache; keep release builds packageable.
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    // Psiphon tunnel core (gomobile AAR), embedded so the app needs no separate Psiphon client.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
