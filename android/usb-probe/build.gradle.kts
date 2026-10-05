plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    // QR decoding for pairing: pinned ZXing core (Apache-2.0, pure Java, no transitive deps).
    implementation("com.google.zxing:core:3.3.3")
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "dev.chinchillacam.usbprobe"
    compileSdk = 33

    defaultConfig {
        applicationId = "dev.chinchillacam.usbprobe"
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
