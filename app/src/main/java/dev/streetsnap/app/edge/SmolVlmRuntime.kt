package dev.streetsnap.app.edge

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Real image + text inference through the three official SmolVLM ONNX graphs, on CPU. */
class SmolVlmRuntime(private val installed: InstalledEdgeModel) : CandidateRanker {
    private val environment: OrtEnvironment
    private val vision: OrtSession
    private val embeddings: OrtSession
    private val decoder: OrtSession
    private val tokenizer: ByteBpeTokenizer
    private val imageToken: Long
    private val endToken: Int
    private val imageSide: Int
    private val hiddenSize: Int
    private val kvHeads: Int
    private val headSize: Int
    private val layers: Int
    private val cacheOutputs: Map<String, String>
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val activeOptions = AtomicReference<OrtSession.RunOptions?>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        // This is a user-visible, on-demand action. BACKGROUND priority makes the
        // native ORT kernels inherit a large scheduler penalty on big.LITTLE phones
        // (and was especially costly on the AVD). The work is already isolated from
        // the main thread and the camera keeps its own executor, so keep normal priority.
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT); runnable.run() }, "street-snap-vlm")
    }

    init {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "本版端侧模型仅支持 ARM64 设备" }
        val config = readConfig("config.json")
        require(config.optString("model_type") in setOf("smolvlm", "idefics3")) { "模型架构不受支持" }
        val text = config.getJSONObject("text_config")
        val sight = config.getJSONObject("vision_config")
        hiddenSize = text.getInt("hidden_size")
        layers = text.getInt("num_hidden_layers")
        kvHeads = text.getInt("num_key_value_heads")
        headSize = text.optInt("head_dim", hiddenSize / text.getInt("num_attention_heads"))
        imageSide = sight.getInt("image_size")
        require(imageSide in 64..512 && hiddenSize in 128..2048 && layers in 1..40 && kvHeads in 1..16 && headSize in 16..256)
        imageToken = config.getLong("image_token_id")
        tokenizer = ByteBpeTokenizer(File(installed.directory, "tokenizer.json"))
        require(tokenizer.specialId("<image>").toLong() == imageToken)
        endToken = tokenizer.specialId("<end_of_utterance>")
        val processor = readConfig("preprocessor_config.json")
        require(processor.optBoolean("do_normalize", true) && processor.optBoolean("do_rescale", true))
        for (field in listOf("image_mean", "image_std")) {
            val values = processor.getJSONArray(field)
            require(values.length() == 3 && (0..2).all { kotlin.math.abs(values.getDouble(it) - .5) < .00001 }) {
                "此模型的图像归一化配置不受支持"
            }
        }
        environment = OrtEnvironment.getEnvironment()
        val opened = mutableListOf<OrtSession>()
        try {
            // Keep a bounded pool: two threads on a four-core emulator, up to four
            // on modern phones. The old fixed value of two underused the big cores.
            val intraOpThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            fun session(path: String, useXnnpack: Boolean, threads: Int): OrtSession {
                return try {
                    OrtSession.SessionOptions().use { options ->
                        if (useXnnpack) {
                            // XNNPACK is present in the trimmed ARM64 ORT runtime and
                            // is particularly effective for the FP32 vision encoder.
                            // Unsupported nodes are automatically kept on CPU.
                            options.addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                        }
                        // XNNPACK owns its own worker pool; keeping ORT's pool at
                        // one avoids nested oversubscription in the vision graph.
                        options.setIntraOpNumThreads(threads)
                        options.setInterOpNumThreads(1)
                        // Extended/all graph fusions are paid once at session creation
                        // and reduce work on every sampled frame, including KV-cache.
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        environment.createSession(path, options)
                    }
                } catch (error: Throwable) {
                    // Some vendor ORT builds reject XNNPACK for a graph with an
                    // unsupported dynamic op. Retry that session with CPU only; this
                    // keeps model installation and inference fail-closed on such devices.
                    if (useXnnpack) session(path, useXnnpack = false, threads = intraOpThreads) else throw error
                }
            }
            session(File(installed.directory, installed.plan.visionFile).absolutePath, useXnnpack = true, threads = 1).also { vision = it; opened += it }
            session(File(installed.directory, installed.plan.embeddingFile).absolutePath, useXnnpack = false, threads = intraOpThreads).also { embeddings = it; opened += it }
            session(File(installed.directory, installed.plan.decoderFile).absolutePath, useXnnpack = false, threads = intraOpThreads).also { decoder = it; opened += it }
            require(vision.inputNames.containsAll(listOf("pixel_values", "pixel_attention_mask"))) { "视觉模型接口不兼容" }
            require(embeddings.inputNames == setOf("input_ids")) { "文本嵌入接口不兼容" }
            require(decoder.inputNames.containsAll(listOf("inputs_embeds", "attention_mask", "position_ids"))) { "解码器接口不兼容" }
            require((decoder.inputInfo.getValue("inputs_embeds").info as TensorInfo).type == OnnxJavaType.FLOAT) { "需要 FP32 输入的 q4 模型" }
            require(decoder.inputNames.count { it.startsWith("past_key_values.") } == layers * 2) { "模型 KV 缓存接口不兼容" }
            cacheOutputs = decoder.inputNames.filter { it.startsWith("past_key_values.") }.associateWith { input ->
                val suffix = input.removePrefix("past_key_values.")
                decoder.outputNames.singleOrNull { it.startsWith("present") && it.endsWith(".$suffix") }
                    ?: error("模型缺少或含有重复 KV 缓存输出")
            }
        } catch (error: Throwable) {
            opened.asReversed().forEach { runCatching { it.close() } }
            worker.shutdown()
            throw error
        }
    }

    /** Busy calls return null. Cancellation terminates ORT; close waits for any native call to finish. */
    suspend fun suggest(bitmap: Bitmap, prompt: String = DEFAULT_PROMPT): CompositionSuggestion? =
        runImageTask(bitmap) { frame, options -> infer(frame, prompt.take(800), options) }

    /** One image pass and one prefill; a null selection means the model abstained. */
    override suspend fun rankCandidates(sheet: Bitmap): CropRanking? =
        runImageTask(sheet, ::rank)

    private suspend fun <T> runImageTask(bitmap: Bitmap, action: (ImageInput, OrtSession.RunOptions) -> T?): T? {
        check(!closed.get()) { "模型已卸载" }
        if (!running.compareAndSet(false, true)) return null
        val frame = try { withContext(Dispatchers.Default) { prepareImage(bitmap) } }
        catch (error: Throwable) { running.set(false); throw error }
        return suspendCancellableCoroutine { continuation ->
            val options = OrtSession.RunOptions()
            activeOptions.set(options)
            continuation.invokeOnCancellation { runCatching { options.setTerminate(true) } }
            try {
                worker.execute {
                    var result: T? = null
                    var failure: Throwable? = null
                    try {
                        result = synchronized(lock) {
                            check(!closed.get()) { "模型已卸载" }
                            if (continuation.isActive) action(frame, options) else null
                        }
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        // Native work and its tensors have finished before this
                        // point. Publish availability before resuming the caller,
                        // so an immediate next request cannot spuriously see busy.
                        // Cancellation alone never releases this gate early.
                        activeOptions.compareAndSet(options, null)
                        try { options.close() }
                        catch (error: Throwable) {
                            val original = failure
                            if (original == null) failure = error
                            else if (original !== error) original.addSuppressed(error)
                        } finally { running.set(false) }
                    }
                    if (continuation.isActive) {
                        val error = failure
                        if (error != null) continuation.resumeWithException(error)
                        else continuation.resume(result)
                    }
                }
            } catch (error: Throwable) {
                activeOptions.compareAndSet(options, null)
                try { options.close() }
                catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                finally { running.set(false) }
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private data class ImageInput(val pixels: FloatArray, val mask: ByteArray, val width: Int, val height: Int)

    private fun prepareImage(source: Bitmap): ImageInput {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        val scale = imageSide.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).roundToInt().coerceIn(1, imageSide)
        val height = (source.height * scale).roundToInt().coerceIn(1, imageSide)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val rgb = IntArray(width * height)
            scaled.getPixels(rgb, 0, width, 0, 0, width, height)
            val plane = imageSide * imageSide
            // Official single-image preprocessing: RGB / 255, mean/std .5, bottom/right padding.
            // Image splitting is deliberately disabled to bound the visual token budget.
            val pixels = FloatArray(plane * 3)
            val mask = ByteArray(plane)
            for (y in 0 until height) for (x in 0 until width) {
                val color = rgb[y * width + x]
                val index = y * imageSide + x
                pixels[index] = ((color ushr 16) and 255) / 127.5f - 1f
                pixels[index + plane] = ((color ushr 8) and 255) / 127.5f - 1f
                pixels[index + 2 * plane] = (color and 255) / 127.5f - 1f
                mask[index] = 1
            }
            return ImageInput(pixels, mask, width, height)
        } finally { if (scaled !== source) scaled.recycle() }
    }

    private fun encodeImage(frame: ImageInput, options: OrtSession.RunOptions): FloatArray {
        val pixelRank = (vision.inputInfo.getValue("pixel_values").info as TensorInfo).shape.size
        val maskRank = (vision.inputInfo.getValue("pixel_attention_mask").info as TensorInfo).shape.size
        require(pixelRank == 5 && maskRank == 4) { "视觉模型输入维度不兼容" }
        val features: FloatArray
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(frame.pixels), longArrayOf(1, 1, 3, imageSide.toLong(), imageSide.toLong())).use { pixels ->
            OnnxTensor.createTensor(environment, ByteBuffer.wrap(frame.mask), longArrayOf(1, 1, imageSide.toLong(), imageSide.toLong()), OnnxJavaType.BOOL).use { mask ->
                vision.run(mapOf("pixel_values" to pixels, "pixel_attention_mask" to mask), options).use { output ->
                    val tensor = output.tensor("image_features")
                    require(tensor.info.shape.last() == hiddenSize.toLong()) { "视觉特征维度不兼容" }
                    val buffer = requireNotNull(tensor.floatBuffer)
                    features = FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        }
        require(features.isNotEmpty() && features.size % hiddenSize == 0 && features.all { it.isFinite() }) {
            "视觉特征无效"
        }
        return features
    }

    private fun rank(frame: ImageInput, options: OrtSession.RunOptions): CropRanking? {
        val startedAt = SystemClock.elapsedRealtime()
        var visionMs = 0L
        var prefillMs = 0L
        var completed = false
        var ranking: CropRanking? = null
        val temporary = mutableListOf<OnnxTensor>()
        try {
            // Score an explicit answer continuation. Bare "A" at the beginning of
            // unrestricted prose can instead mean the English indefinite article.
            val labelTokens = listOf(" A", " B", " C").map { label ->
                tokenizer.encode(label).also { require(it.size == 1) { "模型候选标签必须是单个 token" } }.single().toInt()
            }.toIntArray()
            logRanking("stage=rank_vision image=${frame.width}x${frame.height}")
            val features = encodeImage(frame, options)
            val imageTokens = features.size / hiddenSize
            require(imageTokens in 1..256) { "视觉 token 数超出限制" }
            visionMs = SystemClock.elapsedRealtime() - startedAt
            logRanking("rank_vision_ms=$visionMs image_tokens=$imageTokens")
            val imagePrompt = "<fake_token_around_image><global-img>" + "<image>".repeat(imageTokens) + "<fake_token_around_image>"
            val ids = tokenizer.encode("<|im_start|>User:$imagePrompt$RANKING_PROMPT<end_of_utterance>\nAssistant: Best crop:")
            require(ids.size <= 512 && ids.count { it == imageToken } == imageTokens) { "候选提示过长或图像 token 不匹配" }
            val prefillStartedAt = SystemClock.elapsedRealtime()
            val tokenTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
                .also { temporary += it }
            val values = embeddings.run(mapOf("input_ids" to tokenTensor), options).use { output ->
                requireNotNull(output.tensor("inputs_embeds").floatBuffer).let { buffer ->
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
            require(values.size == ids.size * hiddenSize)
            var source = 0
            for (token in ids.indices) if (ids[token] == imageToken) {
                features.copyInto(values, token * hiddenSize, source, source + hiddenSize)
                source += hiddenSize
            }
            val inputEmbedding = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
                longArrayOf(1, ids.size.toLong(), hiddenSize.toLong())).also { temporary += it }
            val attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }),
                longArrayOf(1, ids.size.toLong())).also { temporary += it }
            val positions = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { it.toLong() }),
                longArrayOf(1, ids.size.toLong())).also { temporary += it }
            val feeds = linkedMapOf("inputs_embeds" to inputEmbedding, "attention_mask" to attention, "position_ids" to positions)
            if ("use_cache_branch" in decoder.inputNames) {
                feeds["use_cache_branch"] = OnnxTensor.createTensor(environment, ByteBuffer.wrap(byteArrayOf(0)),
                    longArrayOf(1), OnnxJavaType.BOOL).also { temporary += it }
            }
            for (name in cacheOutputs.keys) feeds[name] = emptyCacheTensor(name).also { temporary += it }
            // No token-generation loop and no KV outputs: all three choices come
            // from this same real image-conditioned forward pass.
            decoder.run(feeds, setOf("logits"), options).use { output ->
                val logits = output.tensor("logits")
                val vocabulary = logits.info.shape.last().toInt()
                require(vocabulary in 1..100_000)
                val scores = requireNotNull(logits.floatBuffer)
                require(scores.remaining() >= vocabulary)
                scores.position(scores.limit() - vocabulary)
                val lastLogits = FloatArray(vocabulary).also { scores.get(it) }
                prefillMs = SystemClock.elapsedRealtime() - prefillStartedAt
                ranking = CropRankingScores.fromLogits(lastLogits, labelTokens)
                completed = true
            }
            ranking?.let { result ->
                logRanking("rank_selected=${result.selectedIndex} support=${result.support} margin=${result.margin} " +
                    "probabilities=${result.probabilities} raw_scores=${result.rawScores} " +
                    "min_support=${CropRankingScores.MIN_SUPPORT} min_margin=${CropRankingScores.MIN_MARGIN}")
            }
            return ranking
        } finally {
            temporary.asReversed().forEach { it.close() }
            logRanking("rank_total_ms=${SystemClock.elapsedRealtime() - startedAt} vision_ms=$visionMs " +
                "prefill_ms=$prefillMs completed=$completed selected=${ranking?.selectedIndex}")
        }
    }

    private fun logRanking(message: String) {
        // Explicit local opt-in; never log source images, scene descriptions or
        // frame fingerprints. The records contain scores and timings only.
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, message)
    }

    private fun infer(frame: ImageInput, prompt: String, options: OrtSession.RunOptions): CompositionSuggestion? {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(LOG_TAG, "stage=vision image=${frame.width}x${frame.height}")
        val features = encodeImage(frame, options)
        val imageTokens = features.size / hiddenSize
        require(imageTokens in 1..256) { "视觉 token 数超出限制" }
        val visionMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(LOG_TAG, "vision_ms=$visionMs image_tokens=$imageTokens image=${frame.width}x${frame.height}")
        val imagePrompt = "<fake_token_around_image><global-img>" + "<image>".repeat(imageTokens) + "<fake_token_around_image>"
        var ids = tokenizer.encode("<|im_start|>User:$imagePrompt$prompt<end_of_utterance>\nAssistant:")
        require(ids.size <= 512 && ids.count { it == imageToken } == imageTokens) { "构图提示过长或图像 token 不匹配" }
        val generated = ArrayList<Int>()
        var past: OrtSession.Result? = null
        var position = 0
        var prefillMs = 0L
        var decodeMs = 0L
        var completed = false
        var valid = false
        fun finish(): CompositionSuggestion? {
            completed = true
            val response = tokenizer.decode(generated)
            // Raw generated text stays opt-in for local diagnostics, because it can
            // describe a private camera scene. Normal logs contain only timings.
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, "answer=${response.take(256).replace('\n', ' ')}")
            return remap(CompositionSuggestion.parse(response), frame).also { valid = it != null }
        }
        try {
            for (step in 0 until MAX_OUTPUT_TOKENS) {
                val stepStartedAt = SystemClock.elapsedRealtime()
                val temporary = mutableListOf<OnnxTensor>()
                var embeddingResult: OrtSession.Result? = null
                try {
                    val tokenTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).also { temporary += it }
                    embeddingResult = embeddings.run(mapOf("input_ids" to tokenTensor), options)
                    val embedded = embeddingResult.tensor("inputs_embeds")
                    val values = requireNotNull(embedded.floatBuffer).let { buffer ->
                        FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    require(values.size == ids.size * hiddenSize)
                    if (step == 0) {
                        var source = 0
                        for (token in ids.indices) if (ids[token] == imageToken) {
                            features.copyInto(values, token * hiddenSize, source, source + hiddenSize)
                            source += hiddenSize
                        }
                    }
                    // Keep decoder feeds at its declared FP32 input type even if a future
                    // embedding export uses FP16. The copy is bounded to the current token(s).
                    val inputEmbedding = OnnxTensor.createTensor(
                        environment, FloatBuffer.wrap(values), longArrayOf(1, ids.size.toLong(), hiddenSize.toLong())
                    ).also { temporary += it }
                    val attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(position + ids.size) { 1L }), longArrayOf(1, (position + ids.size).toLong())).also { temporary += it }
                    val positions = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { position.toLong() + it }), longArrayOf(1, ids.size.toLong())).also { temporary += it }
                    val feeds = linkedMapOf("inputs_embeds" to inputEmbedding, "attention_mask" to attention, "position_ids" to positions)
                    if ("use_cache_branch" in decoder.inputNames) {
                        feeds["use_cache_branch"] = OnnxTensor.createTensor(environment, ByteBuffer.wrap(byteArrayOf(if (step > 0) 1 else 0)), longArrayOf(1), OnnxJavaType.BOOL).also { temporary += it }
                    }
                    for (layer in 0 until layers) for (kind in listOf("key", "value")) {
                        val name = "past_key_values.$layer.$kind"
                        val cached = past?.let { output ->
                            output.tensor(cacheOutputs.getValue(name))
                        } ?: emptyCacheTensor(name).also { temporary += it }
                        feeds[name] = cached
                    }
                    val next = decoder.run(feeds, options)
                    past?.close()
                    past = next
                    val cacheShape = next.tensor(cacheOutputs.getValue("past_key_values.0.key")).info.shape
                    require(cacheShape.size == 4 && cacheShape[2] == (position + ids.size).toLong()) {
                        "模型 KV 缓存序列长度不匹配"
                    }
                    val stepMs = SystemClock.elapsedRealtime() - stepStartedAt
                    if (step == 0) {
                        prefillMs = stepMs
                        Log.i(LOG_TAG, "prefill_ms=$prefillMs prompt_tokens=${ids.size}")
                    } else decodeMs += stepMs
                    val logits = next.tensor("logits")
                    val shape = logits.info.shape
                    val vocabulary = shape.last().toInt()
                    require(vocabulary in 1..100_000)
                    val scores = requireNotNull(logits.floatBuffer)
                    val offset = scores.limit() - vocabulary
                    var best = 0
                    var value = Float.NEGATIVE_INFINITY
                    for (token in 0 until vocabulary) {
                        val score = scores.get(offset + token)
                        if (score > value) { value = score; best = token }
                    }
                    if (!value.isFinite() || best == endToken || best == 2) break
                    generated += best
                    val response = tokenizer.decode(generated)
                    if (']' in response) return finish()
                    position += ids.size
                    ids = longArrayOf(best.toLong())
                } finally {
                    temporary.forEach { it.close() }
                    embeddingResult?.close()
                }
            }
            return finish()
        } finally {
            past?.close()
            Log.i(LOG_TAG, "total_ms=${SystemClock.elapsedRealtime() - startedAt} vision_ms=$visionMs " +
                "prefill_ms=$prefillMs decode_ms=$decodeMs output_tokens=${generated.size} completed=$completed valid=$valid")
        }
    }

    private fun remap(suggestion: CompositionSuggestion?, frame: ImageInput): CompositionSuggestion? {
        suggestion ?: return null
        val left = (suggestion.left * imageSide / frame.width).coerceIn(0f, 1f)
        val right = (suggestion.right * imageSide / frame.width).coerceIn(0f, 1f)
        val top = (suggestion.top * imageSide / frame.height).coerceIn(0f, 1f)
        val bottom = (suggestion.bottom * imageSide / frame.height).coerceIn(0f, 1f)
        return if (right - left >= .1f && bottom - top >= .1f) suggestion.copy(left = left, top = top, right = right, bottom = bottom) else null
    }

    private fun emptyCacheTensor(name: String): OnnxTensor {
        val type = (decoder.inputInfo.getValue(name).info as TensorInfo).type
        val shape = longArrayOf(1, kvHeads.toLong(), 0, headSize.toLong())
        return when (type) {
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), shape)
            OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16 ->
                OnnxTensor.createTensor(environment, ShortBuffer.allocate(0), shape, type)
            else -> error("模型 KV 缓存类型不兼容：$type")
        }
    }

    private fun readConfig(name: String): JSONObject = File(installed.directory, name).let {
        require(it.length() in 1..1_000_000L) { "模型配置文件无效" }
        JSONObject(it.readText(Charsets.UTF_8))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeOptions.get()?.let { runCatching { it.setTerminate(true) } }
        synchronized(lock) {
            decoder.close()
            embeddings.close()
            vision.close()
        }
        worker.shutdown()
        // OrtEnvironment is a process-wide singleton, not owned by a model session.
    }

    private fun OrtSession.Result.tensor(name: String): OnnxTensor =
        get(name).orElse(null) as? OnnxTensor ?: error("模型缺少 $name 输出")

    companion object {
        private const val LOG_TAG = "StreetSnapVlm"
        private const val RANKING_PROMPT = "Choose crop A, B or C. Preserve the main content, " +
            "avoid cutting important parts, and balance empty space. The rule of thirds is optional. " +
            "Reply with one letter."
        // Digits are separate tokens. Four coordinates plus spaces, punctuation,
        // and a short answer prefix can exceed 24 tokens, so permit a complete
        // tuple while the caller independently bounds each sampled-frame request.
        const val MAX_OUTPUT_TOKENS = 40
        const val DEFAULT_PROMPT = "Locate the main subject in this image. " +
            "Return its bounding box as [left,top,right,bottom], using integers from 0 to 1000 " +
            "relative to the full image. Return only the four coordinates."
    }
}
