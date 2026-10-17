plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val isCiBuild = providers.gradleProperty("ciBuild").map { it.toBoolean() }.getOrElse(false)
val distributionSigning = listOf(
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val hasDistributionSigning = distributionSigning.values.all { it != null }
check(hasDistributionSigning || distributionSigning.values.all { it == null }) {
    "Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
}

android {
    namespace = "dev.streetsnap.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.streetsnap.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0-dev"
        resValue("string", "app_name", "途帧")
    }

    if (hasDistributionSigning) {
        signingConfigs.create("distribution") {
            storeFile = file(requireNotNull(distributionSigning["ANDROID_KEYSTORE_PATH"]))
            storePassword = distributionSigning["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = distributionSigning["ANDROID_KEY_ALIAS"]
            keyPassword = distributionSigning["ANDROID_KEY_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            if (isCiBuild) {
                applicationIdSuffix = ".ci"
                resValue("string", "app_name", "途帧 CI")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasDistributionSigning) signingConfig = signingConfigs.getByName("distribution")
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            if (isCiBuild) {
                applicationIdSuffix = ".ci"
                resValue("string", "app_name", "途帧 CI")
            }
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(project(":camera"))
    implementation(project(":color"))
    // Small ARM64-only ONNX Runtime slice. The app never downloads this runtime;
    // the optional VLM weights are fetched only after an explicit phone action.
    implementation(files("libs/onnxruntime-android-1.22.0-arm64.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
