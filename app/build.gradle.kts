plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.caesiumstudio.bitstream"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.caesiumstudio.bitstream"
        minSdk = 21
        targetSdk = 36
        versionCode = 12
        versionName = "12"
    }

    signingConfigs {
        create("release") {
            storeFile = file("/Users/D059976/Store/Projects/VSCode/cstools/ApkSigningKeys/Linux/AndroidKeyStore/BrokenStream/keystore.jks")
            storePassword = "ravi#1987"
            keyAlias = "rkskey"
            keyPassword = "ravi#1987"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}