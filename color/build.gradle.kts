plugins {
    id("com.android.library")
}

android {
    namespace = "dev.streetsnap.color"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("androidx.camera:camera-core:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
