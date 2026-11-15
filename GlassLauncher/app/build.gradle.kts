plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing comes from the environment (GitHub Actions decodes the keystore secret into
// KEYSTORE_FILE). Without it, release builds fall back to the debug key so local builds work.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")
val ciBuildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.siso.glasslauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.siso.glasslauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = ciBuildNumber
        versionName = "1.1.$ciBuildNumber"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Release build = R8-optimized Compose. Much smoother than debug on a TV box.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // QR code for the "add wallpaper photos from the phone" page.
    implementation("com.google.zxing:core:3.5.3")
}
