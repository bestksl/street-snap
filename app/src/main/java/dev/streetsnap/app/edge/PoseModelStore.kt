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

/**
 * Bounded HTTPS download of the pinned pose .task bundle into app-private storage
 * (filesDir/pose-models/<id>/). Size and SHA-256 are both pinned, so a changed upstream
 * file fails verification instead of silently changing behaviour. No download starts in
 * a constructor.
 */
class PoseModelStore(context: Context) {
    private val root = File(context.filesDir, "pose-models")

    fun modelFile(entry: PoseModelEntry): File = File(File(root, entry.id), "model.task")

    /** A complete file with matching size + digest; null otherwise. */
    fun installed(entry: PoseModelEntry): File? = runCatching {
        val file = modelFile(entry)
        if (!file.isFile || file.length() != entry.bytes) return null
        if (sha256(file) != entry.sha256) return null
        file
    }.getOrNull()

    /** Explicit user action only. */
    suspend fun download(
        entry: PoseModelEntry,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(PoseModelCatalog.models.contains(entry)) { "未知姿势模型" }
        root.mkdirs()
        require(StatFs(root.absolutePath).availableBytes > entry.bytes + 64L * 1024 * 1024) { "可用空间不足" }
        val target = modelFile(entry)
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val connection = (URL(entry.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "street-snap/pose-model-download")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "下载失败（${connection.responseCode}）" }
            val length = connection.contentLengthLong
            require(length < 0 || length == entry.bytes) { "姿势模型大小与目录不符" }
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
                        require(copied <= entry.bytes) { "姿势模型响应超出声明大小" }
                        output.write(buffer, 0, count)
                        if (copied - lastProgress >= 512 * 1024 || copied == entry.bytes) {
                            onProgress(copied, entry.bytes)
                            lastProgress = copied
                        }
                    }
                }
            }
            require(copied == entry.bytes) { "姿势模型下载不完整" }
            require(sha256(partial) == entry.sha256) { "姿势模型校验失败，请重新下载" }
            target.delete()
            require(partial.renameTo(target)) { "无法保存姿势模型" }
            target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    suspend fun remove(entry: PoseModelEntry) = withContext(Dispatchers.IO) {
        File(root, entry.id).deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
