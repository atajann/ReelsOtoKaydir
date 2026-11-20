plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ates.reelsauto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ates.reelsauto"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // CSV dışa aktarma (FileProvider)
    implementation("androidx.core:core:1.13.1")
    // Cihaz içi (internetsiz) yapay zekâ modelleri — APK'nın içine gömülü gelir
    implementation("com.google.mlkit:language-id:17.0.6")        // dil tespiti
    implementation("com.google.mlkit:text-recognition:16.0.1")   // ekrandaki yazıyı okuma (OCR)
    implementation("com.google.mlkit:image-labeling:17.0.9")     // görsel etiketleme
}
