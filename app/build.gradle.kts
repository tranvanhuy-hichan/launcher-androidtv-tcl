import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.qbh21.vitrine"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.qbh21.vitrine"
        minSdk = 30
        targetSdk = 30   // Android 11, the most common Android TV version on budget sets
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        // Optimised build for everyday use: R8-shrunk and not debuggable, so it starts faster and uses
        // less memory. Signed with the debug key so it installs over the debug build and keeps its data.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Sideloaded onto one TV, never published: don't fail release builds on Play-store lint rules
    // (e.g. the Android 11 target).
    lint { checkReleaseBuilds = false }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.google.zxing:core:3.5.3")   // draws the QR code for the phone-settings screen
}
