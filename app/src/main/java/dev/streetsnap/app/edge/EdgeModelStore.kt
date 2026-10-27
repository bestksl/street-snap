package dev.streetsnap.app.edge

import android.content.Context
import android.os.StatFs
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bounded HTTPS downloads into app-private storage. No download starts in the constructor. */
class EdgeModelStore(context: Context) {
    private val root = File(context.filesDir, "edge-models")
    private val assets: AssetManager = context.assets

    /**
     * Anonymous, no-auth download sources in priority order. hf-mirror.com is a domestic
     * reverse proxy of huggingface.co that serves LFS bytes directly on China networks;
     * the official origin stays last for overseas networks. The URL shape is identical on
     * both hosts, and every file is still verified by its pinned SHA-256 / git SHA-1, so a
     * mirror can never serve altered weights.
     */
    private val bases = listOf("https://hf-mirror.com", "https://huggingface.co")

    /**
     * Metadata only; never downloads weights. A release ships the pinned file manifest as
     * an APK asset (a few KB), so this returns offline and survives HuggingFace being
     * unreachable. The live API is only a fallback for an APK built without that asset.
     */
    suspend fun prepare(model: EdgeModelEntry): ModelDownloadPlan = withContext(Dispatchers.IO) {
        require(EdgeModelCatalog.models.contains(model)) { "未知模型来源" }
        bundledPlan(model)?.let { return@withContext it }
        fetchPlan(model)
    }

    private suspend fun fetchPlan(model: EdgeModelEntry): ModelDownloadPlan {
        var lastError: Exception? = null
        for (base in bases) {
            try {
                val info = JSONObject(readSmall("$base/api/models/${model.repository}"))
                val revision = info.getString("sha")
                require(revision.matches(Regex("[a-f0-9]{40}"))) { "模型版本号无效" }
                require(revision == model.revision) { "模型仓库版本已变化，请更新应用内目录" }
                val tree = JSONArray(readSmall(
                    "$base/api/models/${model.repository}/tree/$revision?recursive=true&limit=1000"))
                return parseTree(model, revision, tree)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw (lastError ?: IOException("无法读取模型目录"))
    }

    private fun parseTree(model: EdgeModelEntry, revision: String, tree: JSONArray): ModelDownloadPlan {
        val all = buildList {
            for (i in 0 until tree.length()) {
                val item = tree.getJSONObject(i)
                if (item.optString("type") != "file") continue
                val path = item.getString("path")
                if (!safePath(path)) continue
                val lfs = item.optJSONObject("lfs")
                val digest = lfs?.optString("oid")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                val fallback = item.optString("oid").takeIf { it.matches(Regex("[a-f0-9]{40}")) }
                    ?: item.optString("blobId").takeIf { it.matches(Regex("[a-f0-9]{40}")) }
                if (digest == null && fallback == null) continue
                add(ModelFile(path, item.getLong("size"), digest ?: requireNotNull(fallback), digest != null))
            }
        }
        // The only catalog ranker is the Liquid d1-omni decision model: fp16 SigLIP2
        // vision tower, Q4 block-gather embeddings and the 4-bit MatMulNBits decision
        // graph. The official 8-bit decision graph cannot run on ORT 1.22 (its nodes
        // lack accuracy_level=4, so ORT takes the unpacked path that only supports
        // 4-bit); q4 is the supported path.
        val graphs = listOf(
            "onnx/vision_encoder_fp16.onnx",
            "onnx/embed_tokens_quantized.onnx",
            "onnx/decision_q4.onnx",
        )
        // The LFM Open License requires the license text to travel with the weights.
        val required = listOf("config.json", "tokenizer.json", "tokenizer_config.json",
            "preprocessor_config.json", "LICENSE")
        require(graphs.all { expected -> all.any { it.path == expected } }) { "模型 ONNX 图不完整" }
        val (vision, embedding, decoder) = graphs
        require(required.all { expected -> all.any { it.path == expected } }) { "模型配套配置不完整" }
        val optional = listOf("processor_config.json", "generation_config.json", "LICENSE", "LICENSE.txt")
        val files = all.filter { file -> file.path in required || file.path in optional || graphs.any {
            file.path == it || file.path.startsWith("${it}_data") || file.path == "$it.data"
        } }
        require(files.all { it.bytes in 1..MAX_FILE_BYTES }) { "模型文件大小超出支持范围" }
        require(files.sumOf { it.bytes } in 1..MAX_TOTAL_BYTES) { "模型包过大" }
        return ModelDownloadPlan(model, revision, files, vision, embedding, decoder)
    }

    /** Caller must invoke this only in response to the user's explicit download action. */
    suspend fun download(plan: ModelDownloadPlan, onProgress: (ModelDownloadProgress) -> Unit = {}): InstalledEdgeModel =
        withContext(Dispatchers.IO) {
            validatePlan(plan)
            root.mkdirs()
            require(StatFs(root.absolutePath).availableBytes > plan.totalBytes + 64L * 1024 * 1024) { "可用空间不足" }
            val folder = File(root, "${plan.model.id}-${plan.revision}").apply { mkdirs() }
            var complete = 0L
            for (spec in plan.files) {
                currentCoroutineContext().ensureActive()
                val destination = File(folder, spec.path)
                if (destination.isFile && verify(destination, spec)) {
                    complete += spec.bytes
                    onProgress(ModelDownloadProgress(complete, plan.totalBytes, spec.path))
                    continue
                }
                destination.parentFile?.mkdirs()
                downloadFile(plan, spec, destination) { copied ->
                    onProgress(ModelDownloadProgress(complete + copied, plan.totalBytes, spec.path))
                }
                complete += spec.bytes
                onProgress(ModelDownloadProgress(complete, plan.totalBytes, spec.path))
            }
            // A complete manifest is the readiness marker. Interrupted installs are never offered.
            val manifest = File(folder, "install.json.part")
            manifest.writeText(encode(plan).toString(), Charsets.UTF_8)
            require(manifest.renameTo(File(folder, "install.json"))) { "无法完成模型安装" }
            InstalledEdgeModel(plan, folder)
        }

    /**
     * Download one file through every anonymous source in order, with HTTP range resume.
     * A network failure keeps the .part file so the next source (or a later retry) can
     * continue from the received offset; a size/hash mismatch discards it and restarts,
     * because the bytes on disk cannot belong to this file.
     */
    private suspend fun downloadFile(
        plan: ModelDownloadPlan,
        spec: ModelFile,
        destination: File,
        onProgress: (Long) -> Unit,
    ) {
        val partial = File(destination.path + ".part")
        var lastError: Exception? = null
        for ((index, base) in bases.withIndex()) {
            currentCoroutineContext().ensureActive()
            val resume = if (partial.isFile) partial.length().coerceAtMost(spec.bytes) else 0L
            val address = "$base/${plan.model.repository}/resolve/${plan.revision}/${spec.path}"
            val connection = connection(address)
            try {
                if (resume in 1 until spec.bytes) {
                    connection.setRequestProperty("Range", "bytes=$resume-")
                }
                val startAt: Long
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> startAt = 0L
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range = connection.getHeaderField("Content-Range").orEmpty().lowercase()
                        require(range.startsWith("bytes $resume-")) { "下载源不支持从断点继续" }
                        startAt = resume
                    }
                    else -> throw IOException("下载失败（$code）")
                }
                java.io.FileOutputStream(partial, startAt > 0).buffered().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = startAt
                        var lastProgress = startAt
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= spec.bytes) { "模型响应超出声明大小" }
                            output.write(buffer, 0, count)
                            if (copied - lastProgress >= 512 * 1024 || copied == spec.bytes) {
                                onProgress(copied)
                                lastProgress = copied
                            }
                        }
                        require(copied == spec.bytes) { "模型下载不完整" }
                    }
                }
                require(verify(partial, spec)) { "模型校验失败，请重新下载" }
                destination.delete()
                require(partial.renameTo(destination)) { "无法保存模型文件" }
                return
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                // A full-but-wrong or over-long .part cannot be resumed on the next source.
                if (partial.isFile && (partial.length() >= spec.bytes) && index < bases.lastIndex) {
                    partial.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw (lastError ?: IOException("所有下载源均不可用"))
    }

    /**
     * Import a model that was deliberately staged in app assets at build time.
     *
     * Assets are copied into the private model store. Different graph variants at the
     * same revision have separate directories, preserving a previously installed model.
     * The manifest is validated before copying and every file is checked by its hash.
     * A manifest-only asset (the shipped pinned manifest, without weights) is not a
     * bundled model: this returns null so normal downloads and existing installs remain
     * available. A valid install with the same graph configuration is reused.
     */
    suspend fun installBundled(model: EdgeModelEntry): InstalledEdgeModel? = withContext(Dispatchers.IO) {
        require(model in EdgeModelCatalog.models) { "未知模型来源" }
        val bundledPlan = bundledPlan(model) ?: return@withContext null
        val assetRoot = "edge-models/${model.id}-${model.revision}"
        // A release ships the manifest only (weights are an explicit download), so the
        // weight assets are absent and this quietly yields to normal downloads / installs.
        if (!bundledPlan.files.all { assetExists("$assetRoot/${it.path}") }) return@withContext null
        installedModels(model).firstOrNull { sameVariant(it.plan, bundledPlan) }
            ?.let { return@withContext it }
        val manifestPath = bundledManifestPath(model)
        val manifestText = readAssetSmall(requireNotNull(manifestPath))

        root.mkdirs()
        require(StatFs(root.absolutePath).availableBytes > bundledPlan.totalBytes + 64L * 1024 * 1024) { "可用空间不足" }
        val folderName = bundledFolderName(bundledPlan)
        val folder = File(root, folderName)
        val staging = File(root, "$folderName.asset-part")
        if (staging.exists()) require(staging.deleteRecursively()) { "无法清理未完成的内置模型" }
        staging.mkdirs()
        try {
            var copiedTotal = 0L
            for (spec in bundledPlan.files) {
                currentCoroutineContext().ensureActive()
                val assetPath = "$assetRoot/${spec.path}"
                require(assetExists(assetPath)) { "内置模型缺少文件：${spec.path}" }
                val destination = File(staging, spec.path)
                destination.parentFile?.mkdirs()
                val partial = File(destination.path + ".part")
                var copied = 0L
                assets.open(assetPath).use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= spec.bytes) { "内置模型文件超出声明大小：${spec.path}" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(copied == spec.bytes && verify(partial, spec)) { "内置模型校验失败：${spec.path}" }
                require(partial.renameTo(destination)) { "无法保存内置模型文件：${spec.path}" }
                copiedTotal += copied
            }
            require(copiedTotal == bundledPlan.totalBytes) { "内置模型大小与清单不一致" }
            val manifest = File(staging, "install.json.part")
            manifest.writeText(manifestText, Charsets.UTF_8)
            require(manifest.renameTo(File(staging, "install.json"))) { "无法保存内置模型清单" }
            // Never replace another valid graph variant, including the legacy Q4
            // directory. Only incomplete data at this variant's own path is replaced.
            val existing = installedModel(folder, model)
            if (existing != null) {
                require(sameVariant(existing.plan, bundledPlan)) { "内置模型目录已被其他有效版本占用" }
                require(staging.deleteRecursively()) { "无法清理内置模型临时文件" }
                return@withContext existing
            }
            if (folder.exists()) require(folder.deleteRecursively()) { "无法替换未完成的内置模型" }
            require(staging.renameTo(folder)) { "无法完成内置模型安装" }
            InstalledEdgeModel(bundledPlan, folder)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun installed(model: EdgeModelEntry): InstalledEdgeModel? {
        val models = installedModels(model)
        val bundled = bundledPlan(model)
        // An APK upgrade may change quantization without changing the pinned revision.
        // Prefer that APK's graphs; a normal APK can still use a previously imported model.
        return bundled?.let { plan -> models.firstOrNull { sameVariant(it.plan, plan) } }
            ?: models.firstOrNull()
    }

    private fun installedModels(model: EdgeModelEntry): List<InstalledEdgeModel> =
        root.listFiles()?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith(model.id + "-") &&
                !it.name.endsWith(".asset-part") }
            ?.sortedBy { it.name }
            ?.mapNotNull { installedModel(it, model) }
            ?.toList().orEmpty()

    private fun installedModel(folder: File, model: EdgeModelEntry): InstalledEdgeModel? = runCatching {
        val manifest = File(folder, "install.json")
        require(manifest.length() in 1..MAX_METADATA_BYTES.toLong())
        val plan = decode(JSONObject(manifest.readText(Charsets.UTF_8)))
        validatePlan(plan)
        require(plan.model == model)
        require(plan.files.all { File(folder, it.path).isFile && File(folder, it.path).length() == it.bytes })
        InstalledEdgeModel(plan, folder)
    }.getOrNull()

    /**
     * Manifest asset locations, in order: a fully bundled weight build stages
     * edge-models/<id>-<rev>/install.json (also carrying the weight files, never in source
     * control); an ordinary release ships only edge-model-manifests/<id>-<rev>.json so
     * prepare() needs no network without any weights entering the APK or git.
     */
    private fun bundledManifestPath(model: EdgeModelEntry): String? = listOf(
        "edge-models/${model.id}-${model.revision}/install.json",
        "edge-model-manifests/${model.id}-${model.revision}.json",
    ).firstOrNull { assetExists(it) }

    private fun bundledPlan(model: EdgeModelEntry): ModelDownloadPlan? {
        val manifestPath = bundledManifestPath(model) ?: return null
        val plan = try {
            decode(JSONObject(readAssetSmall(manifestPath)))
        } catch (error: Exception) {
            throw IllegalArgumentException("内置模型安装清单无效", error)
        }
        validatePlan(plan)
        require(plan.model == model && plan.revision == model.revision) { "内置模型版本与应用目录不一致" }
        require(plan.totalBytes > 0) { "内置模型为空" }
        return plan
    }

    private fun sameVariant(first: ModelDownloadPlan, second: ModelDownloadPlan): Boolean =
        first.model == second.model && first.revision == second.revision &&
            first.visionFile == second.visionFile && first.embeddingFile == second.embeddingFile &&
            first.decoderFile == second.decoderFile

    private fun bundledFolderName(plan: ModelDownloadPlan): String {
        val key = listOf(plan.model.id, plan.revision, plan.visionFile, plan.embeddingFile, plan.decoderFile)
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "${plan.model.id}-${plan.revision}-bundle-${digest.take(24)}"
    }

    /** Call after unloading this model's inference sessions. */
    suspend fun remove(model: EdgeModelEntry) = withContext(Dispatchers.IO) {
        require(model in EdgeModelCatalog.models)
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith(model.id + "-") }?.forEach {
            require(it.deleteRecursively()) { "无法删除模型文件" }
        }
    }

    private suspend fun readSmall(address: String): String {
        val connection = connection(address)
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "无法读取模型目录（${connection.responseCode}）" }
            require(connection.contentLengthLong <= MAX_METADATA_BYTES) { "模型目录过大" }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_METADATA_BYTES) { "模型目录过大" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private fun assetExists(path: String): Boolean = runCatching {
        assets.open(path).use { }
        true
    }.getOrDefault(false)

    private fun readAssetSmall(path: String): String {
        assets.open(path).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_METADATA_BYTES) { "内置模型清单过大" }
                out.write(buffer, 0, count)
            }
            return out.toString("UTF-8")
        }
    }

    private fun connection(address: String): HttpURLConnection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "street-snap/edge-model-download")
        setRequestProperty("Accept-Encoding", "identity")
    }

    private suspend fun verify(file: File, spec: ModelFile): Boolean {
        if (file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance(if (spec.isLfsSha256) "SHA-256" else "SHA-1")
        if (!spec.isLfsSha256) digest.update("blob ${spec.bytes}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == spec.digest
    }

    private fun validatePlan(plan: ModelDownloadPlan) {
        require(plan.model in EdgeModelCatalog.models && plan.revision.matches(Regex("[a-f0-9]{40}")))
        require(plan.files.isNotEmpty() && plan.files.size <= 32 && plan.files.map { it.path }.distinct().size == plan.files.size)
        require(plan.files.all { safePath(it.path) && it.bytes in 1..MAX_FILE_BYTES &&
            it.digest.matches(Regex(if (it.isLfsSha256) "[a-f0-9]{64}" else "[a-f0-9]{40}")) })
        require(plan.totalBytes <= MAX_TOTAL_BYTES)
        require(listOf(plan.visionFile, plan.embeddingFile, plan.decoderFile).all { graph -> plan.files.any { it.path == graph } })
    }

    private fun encode(plan: ModelDownloadPlan) = JSONObject().apply {
        put("model", plan.model.id); put("revision", plan.revision)
        put("vision", plan.visionFile); put("embedding", plan.embeddingFile); put("decoder", plan.decoderFile)
        put("files", JSONArray().apply { plan.files.forEach { file -> put(JSONObject().apply {
            put("path", file.path); put("bytes", file.bytes); put("digest", file.digest); put("sha256", file.isLfsSha256)
        }) } })
    }

    private fun decode(json: JSONObject): ModelDownloadPlan {
        val files = json.getJSONArray("files")
        val graphs = json.optJSONObject("graphs")
        fun graph(name: String): String {
            val direct = json.optString(name).takeIf { it.isNotBlank() }
            return direct ?: graphs?.optString(name)?.takeIf { it.isNotBlank() }
                ?: error("模型安装清单缺少 $name 图")
        }
        return ModelDownloadPlan(
            requireNotNull(EdgeModelCatalog.find(json.getString("model"))), json.getString("revision"),
            (0 until files.length()).map { index -> files.getJSONObject(index).let {
                val digest = it.optString("digest").takeIf { value -> value.isNotBlank() }
                    ?: it.optString("sha256").takeIf { value -> value.isNotBlank() }
                    ?: error("模型安装清单缺少文件摘要")
                ModelFile(it.getString("path"), it.getLong("bytes"), digest,
                    it.optBoolean("sha256", digest.matches(Regex("[a-f0-9]{64}"))))
            } }, graph("vision"), graph("embedding"), graph("decoder"),
        )
    }

    companion object {
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private const val MAX_FILE_BYTES = 768L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 1200L * 1024 * 1024
        private fun safePath(path: String): Boolean = path.length <= 160 &&
            path.matches(Regex("[A-Za-z0-9_./-]+")) && !path.startsWith('/') &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
