plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fedemeju.colortap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fedemeju.colortap"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "4.2"
    }

    // Clave fija para que las actualizaciones se instalen encima sin desinstalar
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
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
    // Reconocimiento de texto sin internet (modo math quiz)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
