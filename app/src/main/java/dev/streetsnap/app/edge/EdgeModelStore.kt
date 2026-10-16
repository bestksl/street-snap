package dev.streetsnap.app.edge

import android.content.Context
import android.os.StatFs
import java.io.File
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

    suspend fun prepare(model: EdgeModelEntry): ModelDownloadPlan = withContext(Dispatchers.IO) {
        require(EdgeModelCatalog.models.contains(model)) { "未知模型来源" }
        val info = JSONObject(readSmall("https://huggingface.co/api/models/${model.repository}"))
        val revision = info.getString("sha")
        require(revision.matches(Regex("[a-f0-9]{40}"))) { "模型版本号无效" }
        require(revision == model.revision) { "模型仓库版本已变化，请更新应用内目录" }
        val tree = JSONArray(readSmall("https://huggingface.co/api/models/${model.repository}/tree/$revision?recursive=true&limit=1000"))
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
        fun graph(stem: String): String = listOf(
            // q4 is smaller for vision/decoder; embeddings have a much smaller
            // official int8/uint8 graph, so prefer it for that one.
            if (stem == "embed_tokens") "onnx/${stem}_quantized.onnx" else "onnx/${stem}_q4.onnx",
            if (stem == "embed_tokens") "onnx/${stem}_q4.onnx" else "onnx/${stem}_quantized.onnx"
        ).firstOrNull { candidate -> all.any { it.path == candidate } }
            ?: error("官方仓库暂无可用的 $stem 小体积 ONNX 文件")
        val vision = graph("vision_encoder")
        val embedding = graph("embed_tokens")
        val decoder = graph("decoder_model_merged")
        val graphs = listOf(vision, embedding, decoder)
        val required = listOf("config.json", "tokenizer.json", "tokenizer_config.json", "preprocessor_config.json")
        require(required.all { expected -> all.any { it.path == expected } }) { "模型配套配置不完整" }
        val optional = listOf("processor_config.json", "generation_config.json", "LICENSE", "LICENSE.txt")
        val files = all.filter { file -> file.path in required || file.path in optional || graphs.any {
            file.path == it || file.path.startsWith("${it}_data") || file.path == "$it.data"
        } }
        require(files.all { it.bytes in 1..MAX_FILE_BYTES }) { "模型文件大小超出支持范围" }
        require(files.sumOf { it.bytes } in 1..MAX_TOTAL_BYTES) { "模型包过大" }
        ModelDownloadPlan(model, revision, files, vision, embedding, decoder)
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
                val partial = File(destination.path + ".part")
                val address = "https://huggingface.co/${plan.model.repository}/resolve/${plan.revision}/${spec.path}"
                val connection = connection(address)
                try {
                    require(connection.responseCode == HttpURLConnection.HTTP_OK) { "下载失败（${connection.responseCode}）" }
                    val length = connection.contentLengthLong
                    require(length < 0 || length == spec.bytes) { "模型文件大小与版本信息不符" }
                    var copied = 0L
                    var lastProgress = 0L
                    connection.inputStream.use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= spec.bytes) { "模型响应超出声明大小" }
                                output.write(buffer, 0, count)
                                if (copied - lastProgress >= 512 * 1024 || copied == spec.bytes) {
                                    onProgress(ModelDownloadProgress(complete + copied, plan.totalBytes, spec.path))
                                    lastProgress = copied
                                }
                            }
                        }
                    }
                    require(copied == spec.bytes && verify(partial, spec)) { "模型校验失败，请重新下载" }
                    destination.delete()
                    require(partial.renameTo(destination)) { "无法保存模型文件" }
                    complete += copied
                } finally {
                    connection.disconnect()
                    partial.delete()
                }
            }
            // A complete manifest is the readiness marker. Interrupted installs are never offered.
            val manifest = File(folder, "install.json.part")
            manifest.writeText(encode(plan).toString(), Charsets.UTF_8)
            require(manifest.renameTo(File(folder, "install.json"))) { "无法完成模型安装" }
            InstalledEdgeModel(plan, folder)
        }

    fun installed(model: EdgeModelEntry): InstalledEdgeModel? = root.listFiles()?.asSequence()
        ?.filter { it.isDirectory && it.name.startsWith(model.id + "-") }
        ?.mapNotNull { folder -> runCatching {
            val manifest = File(folder, "install.json")
            require(manifest.length() in 1..MAX_METADATA_BYTES.toLong())
            val plan = decode(JSONObject(manifest.readText(Charsets.UTF_8)))
            validatePlan(plan)
            require(plan.model == model)
            require(plan.files.all { File(folder, it.path).length() == it.bytes })
            InstalledEdgeModel(plan, folder)
        }.getOrNull() }
        ?.firstOrNull()

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
        return ModelDownloadPlan(
            requireNotNull(EdgeModelCatalog.find(json.getString("model"))), json.getString("revision"),
            (0 until files.length()).map { index -> files.getJSONObject(index).let {
                ModelFile(it.getString("path"), it.getLong("bytes"), it.getString("digest"), it.getBoolean("sha256"))
            } }, json.getString("vision"), json.getString("embedding"), json.getString("decoder"),
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
