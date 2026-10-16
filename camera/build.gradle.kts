plugins {
    id("com.android.library")
}

android {
    namespace = "dev.streetsnap.camera"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    api("androidx.camera:camera-core:1.5.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
