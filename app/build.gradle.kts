plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.krazey.utcomp.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.krazey.utcomp.probe"
        minSdk = 23
        targetSdk = 33
        versionCode = 1
        versionName = "0.1-android13-usb-bt-probe"
    }

    kotlin {
        jvmToolchain(17)
    }
}
