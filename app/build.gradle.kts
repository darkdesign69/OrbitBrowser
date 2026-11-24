plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.redowan.orbit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.redowan.orbit"
        minSdk = 29
        targetSdk = 34
        // always higher than the build before, so Android installs a new build as an update
        versionCode = (System.currentTimeMillis() / 60000).toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build. A random debug key per CI run is what caused
    // "App not installed as package conflicts with an existing package".
    signingConfigs {
        create("orbit") {
            storeFile = file("orbit.keystore")
            storePassword = "orbitbrowser"
            keyAlias = "orbit"
            keyPassword = "orbitbrowser"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("orbit")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("orbit")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
