plugins {
    id("com.android.application")
}

android {
    namespace = "dev.zenitsu.motebridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.zenitsu.motebridge"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The bridge is an executable shipped as lib*.so so Android installs it executable.
    packaging { jniLibs { useLegacyPackaging = true } }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
