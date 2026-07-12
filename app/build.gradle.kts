plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.krazey.utcomp.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.krazey.utcomp.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 10000
        versionName = "1.0.0"
    }

    kotlin {
        jvmToolchain(17)
    }
}
