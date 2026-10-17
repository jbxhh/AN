plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "io.anonero.rusttrial"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.anonero.rusttrial"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-rust-trial"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
