plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    // QR decoding for pairing: pinned ZXing core (Apache-2.0, pure Java, no transitive deps).
    implementation("com.google.zxing:core:3.3.3")
    testImplementation("junit:junit:4.13.2")
}

val releaseKeystore = System.getenv("CHINCHILLACAM_KEYSTORE")
val releaseKeystorePassword = System.getenv("CHINCHILLACAM_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("CHINCHILLACAM_KEY_ALIAS")
val releaseKeyPassword = System.getenv("CHINCHILLACAM_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystore, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "dev.chinchillacam.usbprobe"
    compileSdk = 33

    defaultConfig {
        applicationId = "io.github.oyhamburo.chinchillacam"
        versionCode = 1
        versionName = "0.1.0"
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigning = signingConfigs.create("release") {
        if (hasReleaseSigning) {
            storeFile = file(requireNotNull(releaseKeystore))
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes.getByName("release") {
        if (hasReleaseSigning) signingConfig = releaseSigning
        isMinifyEnabled = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Only packaging release needs a key; debug builds and unit tests remain usable without it.
tasks.matching { it.name in setOf("packageRelease", "assembleRelease", "bundleRelease") }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            throw GradleException("Faltan las variables CHINCHILLACAM_* para firmar el release; ver docs/release.md")
        }
    }
}
