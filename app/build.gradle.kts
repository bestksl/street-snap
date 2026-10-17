plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val isCiBuild = providers.gradleProperty("ciBuild").map { it.toBoolean() }.getOrElse(false)
// Optional local-only model staging. The property is intentionally absent from normal
// builds; when supplied, the model directory is copied into ignored app assets so the
// Android plugin packages it in the APK without putting weights in source control.
val edgeModelDir = providers.gradleProperty("edgeModelDir").orNull?.let { file(it) }
val edgeModelManifest = edgeModelDir?.let { source ->
    require(source.isDirectory) { "edgeModelDir must be a model directory: $source" }
    // Downloads made by the repository verifier use street-snap-manifest.json. The
    // staging task renames that validated manifest to install.json in the APK assets.
    val manifest = source.resolve("install.json").takeIf { it.isFile }
        ?: source.resolve("street-snap-manifest.json").takeIf { it.isFile }
    require(manifest != null) { "edgeModelDir must contain install.json or street-snap-manifest.json: $source" }
    manifest
}
val edgeModelManifestText = edgeModelManifest?.readText()
val edgeModelAssetName = edgeModelManifestText?.let { text ->
    val id = Regex("\\\"model\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]+)\\\"").find(text)?.groupValues?.get(1)
    val revision = Regex("\\\"revision\\\"\\s*:\\s*\\\"([a-f0-9]{40})\\\"").find(text)?.groupValues?.get(1)
    require(!id.isNullOrBlank() && revision != null) {
        "edgeModelDir/install.json must declare model and a 40-character revision"
    }
    "$id-$revision"
}
val edgeModelFilePaths = edgeModelManifestText?.let { text ->
    val paths = Regex("\\\"path\\\"\\s*:\\s*\\\"([A-Za-z0-9_./-]+)\\\"").findAll(text)
        .map { it.groupValues[1] }.toList().distinct()
    require(paths.isNotEmpty()) { "edgeModelDir manifest must declare model files" }
    paths
}
val edgeModelCanonicalManifest = edgeModelManifestText?.let { text ->
    // Keep the APK manifest small and deterministic. The verifier's local manifest
    // uses graphs + sha256 fields; runtime install.json uses direct graph names +
    // digest/sha256 fields. A pre-existing install.json is already canonical.
    if (edgeModelManifest?.name == "install.json") text else {
        fun value(key: String): String = Regex("\\\"$key\\\"\\s*:\\s*\\\"([A-Za-z0-9_./-]+)\\\"")
            .find(text)?.groupValues?.get(1) ?: error("manifest missing $key")
        val graphs = Regex("\\\"graphs\\\"\\s*:\\s*\\{([^}]*)\\}")
            .find(text)?.groupValues?.get(1) ?: error("manifest missing graphs")
        fun graphValue(key: String): String = Regex("\\\"$key\\\"\\s*:\\s*\\\"([A-Za-z0-9_./-]+)\\\"")
            .find(graphs)?.groupValues?.get(1) ?: error("manifest missing graph $key")
        val id = value("model")
        val revision = value("revision")
        val fileBlocks = Regex("\\{[^{}]*\\\"path\\\"\\s*:\\s*\\\"[A-Za-z0-9_./-]+\\\"[^{}]*\\}")
            .findAll(text).map { block ->
                val body = block.value
                val path = Regex("\\\"path\\\"\\s*:\\s*\\\"([A-Za-z0-9_./-]+)\\\"").find(body)!!.groupValues[1]
                val bytes = Regex("\\\"bytes\\\"\\s*:\\s*(\\d+)").find(body)!!.groupValues[1]
                val digest = Regex("\\\"sha256\\\"\\s*:\\s*\\\"([a-f0-9]{64})\\\"").find(body)?.groupValues?.get(1)
                    ?: Regex("\\\"digest\\\"\\s*:\\s*\\\"([a-f0-9]{64})\\\"").find(body)?.groupValues?.get(1)
                    ?: error("manifest file missing SHA-256: $path")
                "{\"path\":\"$path\",\"bytes\":$bytes,\"digest\":\"$digest\",\"sha256\":true}"
            }.toList()
        require(fileBlocks.isNotEmpty()) { "manifest must declare files" }
        "{\"model\":\"$id\",\"revision\":\"$revision\",\"vision\":\"${graphValue("vision")}\",\"embedding\":\"${graphValue("embedding")}\",\"decoder\":\"${graphValue("decoder")}\",\"files\":[${fileBlocks.joinToString(",")}] }"
    }
}
val stageEdgeModelAssets = edgeModelDir?.let { source ->
    val destination = layout.projectDirectory.dir("src/main/assets/edge-models/$edgeModelAssetName").asFile
    tasks.register<Sync>("stageEdgeModelAssets") {
        from(source)
        into(destination)
        // Hugging Face caches are not runtime inputs and can be much larger than the
        // selected graphs. Keep only the manifest and model files in APK assets.
        exclude(".cache/**", "**/.cache/**", "street-snap-manifest.json", "install.json")
        include(*requireNotNull(edgeModelFilePaths).toTypedArray())
        outputs.file(destination.resolve("install.json"))
        doLast {
            destination.resolve("install.json").writeText(requireNotNull(edgeModelCanonicalManifest))
        }
    }
}
if (stageEdgeModelAssets != null) {
    tasks.named("preBuild").configure { dependsOn(stageEdgeModelAssets) }
} else {
    // A previous local model build may have left ignored assets in the source tree.
    // Clear them for ordinary Preview/CI builds so the default APK never inherits
    // a developer's private model weights.
    val clearStagedEdgeModelAssets = tasks.register<Delete>("clearStagedEdgeModelAssets") {
        delete(layout.projectDirectory.dir("src/main/assets/edge-models"))
    }
    tasks.named("preBuild").configure { dependsOn(clearStagedEdgeModelAssets) }
}
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
