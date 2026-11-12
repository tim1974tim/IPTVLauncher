plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    namespace = "com.example.iptvlauncher"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.iptvlauncher"
        minSdk = 23
        targetSdk = 30
        versionCode = 25
        versionName = "0.4.21"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // VideoLAN libVLC: real Android playback engine and native binaries.
    implementation("org.videolan.android:libvlc-all:3.7.7")
}
