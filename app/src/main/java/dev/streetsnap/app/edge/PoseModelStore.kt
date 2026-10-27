package dev.streetsnap.app.edge

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
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

    /**
     * Explicit user action only. Every pinned anonymous source is tried in order and the
     * transfer resumes from the .part offset via HTTP Range, so a flaky network does not
     * restart the file. A size/hash mismatch discards the .part and tries the next source.
     */
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
        var lastError: Exception? = null
        for ((index, address) in entry.downloadUrls.withIndex()) {
            currentCoroutineContext().ensureActive()
            val resume = if (partial.isFile) partial.length().coerceAtMost(entry.bytes) else 0L
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "street-snap/pose-model-download")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (resume in 1 until entry.bytes) connection.setRequestProperty("Range", "bytes=$resume-")
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
                var copied = startAt
                var lastProgress = startAt
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(partial, startAt > 0).buffered().use { output ->
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
                return@withContext target
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (partial.isFile && partial.length() >= entry.bytes && index < entry.downloadUrls.lastIndex) {
                    partial.delete()
                }
            } finally {
                connection.disconnect()
            }
        }
        partial.delete()
        throw (lastError ?: IOException("所有下载源均不可用"))
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
