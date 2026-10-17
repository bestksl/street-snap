package dev.streetsnap.app

import android.content.Context
import android.os.SystemClock
import dev.streetsnap.app.edge.CompositionSuggestion
import dev.streetsnap.app.edge.EdgeModelCatalog
import dev.streetsnap.app.edge.EdgeModelStore
import dev.streetsnap.app.edge.ModelDownloadPlan
import dev.streetsnap.app.edge.SmolVlmRuntime
import dev.streetsnap.camera.CompositionFrameSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ModelInstallStatus { CHECKING, ABSENT, PREPARING, AVAILABLE, DOWNLOADING, INSTALLED, REMOVING, FAILED }
internal enum class AssistStatus { OFF, UNINSTALLED, PAUSED, LOADING, READY, FAILED }
internal data class CompositionUiState(
    val modelId: String = "smolvlm-256m",
    val install: ModelInstallStatus = ModelInstallStatus.CHECKING,
    val bytes: Long? = null,
    val downloadedBytes: Long = 0,
    val installError: String? = null,
    val status: AssistStatus = AssistStatus.OFF,
    val statusText: String? = null,
    val lastInferenceMs: Long? = null,
)
internal data class CompositionOverlay(val suggestion: CompositionSuggestion, val expiresAtMs: Long)

/** Optional inference has its own lifetime and never takes the camera's controls or shutter locks. */
internal class CompositionAssistant(context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private data class Configuration(val id: String, val enabled: Boolean, val foreground: Boolean)
    private val store = EdgeModelStore(context)
    private val _state = MutableStateFlow(CompositionUiState())
    val state = _state.asStateFlow()
    private val _source = MutableStateFlow<CompositionFrameSource?>(null)
    val source = _source.asStateFlow()
    private val _overlay = MutableStateFlow<CompositionOverlay?>(null)
    val overlay = _overlay.asStateFlow()
    private val resultGate = CompositionResultGate()
    private val sessionMutex = Mutex()
    private val inferenceMutex = Mutex()
    @Volatile private var configuration: Configuration? = null
    private val sessionGeneration = AtomicLong(0L)
    private var sessionJob: Job? = null
    private var installJob: Job? = null
    private var expiryJob: Job? = null
    private var plan: ModelDownloadPlan? = null
    private var closed = false

    /** Let CameraX observe the null source and unbind ImageAnalysis before shutting its executor down. */
    private fun retireSource(source: CompositionFrameSource?) {
        if (source == null) return
        source.invalidateFrames()
        scope.launch(NonCancellable) {
            delay(300)
            source.close()
        }
    }

    fun configure(id: String, enabled: Boolean, foreground: Boolean) {
        if (closed) return
        val next = Configuration(EdgeModelCatalog.find(id)?.id ?: "smolvlm-256m", enabled, foreground)
        if (configuration == next) return
        val changedModel = configuration?.id != next.id
        configuration = next
        if (changedModel) {
            installJob?.cancel()
            plan = null
            _state.value = CompositionUiState(modelId = next.id)
        }
        restartSession()
    }

    fun invalidateView() {
        resultGate.invalidate(SystemClock.elapsedRealtime())
        _source.value?.invalidateFrames()
        _overlay.value = null
        expiryJob?.cancel()
    }

    /** Metadata only. A second, explicit install action is required after the size is visible. */
    fun prepareInstall() {
        val config = configuration ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.update { it.copy(install = ModelInstallStatus.PREPARING, installError = null) }
            try {
                val prepared = store.prepare(requireNotNull(EdgeModelCatalog.find(config.id)))
                if (configuration?.id != config.id) return@launch
                plan = prepared
                _state.update { it.copy(install = ModelInstallStatus.AVAILABLE, bytes = prepared.totalBytes) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (configuration?.id == config.id) _state.update { it.copy(install = ModelInstallStatus.FAILED,
                    installError = error.message ?: "无法读取模型安装信息") }
            }
        }
    }

    fun install() {
        val prepared = plan ?: return
        // AVAILABLE is published only after metadata preparation has produced this plan.
        // Do not reject the first tap merely because the preparation coroutine has not
        // completed its final dispatch yet.
        if (prepared.model.id != configuration?.id || _state.value.install != ModelInstallStatus.AVAILABLE) return
        installJob?.cancel()
        installJob = scope.launch {
            _state.update { it.copy(install = ModelInstallStatus.DOWNLOADING, downloadedBytes = 0, installError = null) }
            try {
                store.download(prepared) { progress ->
                    if (configuration?.id == prepared.model.id) _state.update { it.copy(
                        downloadedBytes = progress.downloadedBytes, bytes = progress.totalBytes) }
                }
                if (configuration?.id == prepared.model.id) {
                    _state.update { it.copy(install = ModelInstallStatus.INSTALLED, bytes = prepared.totalBytes) }
                    restartSession()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (configuration?.id == prepared.model.id) _state.update { it.copy(install = ModelInstallStatus.FAILED,
                    installError = error.message ?: "安装未完成") }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        _state.update { it.copy(install = if (plan == null) ModelInstallStatus.ABSENT else ModelInstallStatus.AVAILABLE,
            downloadedBytes = 0, installError = null) }
    }

    fun removeModel() {
        val config = configuration ?: return
        installJob?.cancel()
        sessionJob?.cancel()
        sessionGeneration.incrementAndGet()
        val oldSource = _source.value
        _source.value = null
        retireSource(oldSource)
        invalidateView()
        _state.update { it.copy(install = ModelInstallStatus.REMOVING, status = AssistStatus.UNINSTALLED, statusText = null) }
        installJob = scope.launch {
            try {
                // Runtime cleanup holds this lock until native sessions and any active inference end.
                sessionMutex.withLock { store.remove(requireNotNull(EdgeModelCatalog.find(config.id))) }
                if (configuration?.id == config.id) {
                    plan = null
                    _state.update { it.copy(install = ModelInstallStatus.ABSENT, bytes = null, downloadedBytes = 0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (configuration?.id == config.id) _state.update { it.copy(install = ModelInstallStatus.FAILED,
                    installError = error.message ?: "删除未完成") }
            }
        }
    }

    fun retry() { restartSession() }

    private fun restartSession() {
        val config = configuration ?: return
        if (_state.value.install == ModelInstallStatus.REMOVING) return
        val generation = sessionGeneration.incrementAndGet()
        fun isCurrentSession() = configuration == config && sessionGeneration.get() == generation
        sessionJob?.cancel()
        val oldSource = _source.value
        _source.value = null
        retireSource(oldSource)
        invalidateView()
        _state.update { it.copy(status = when {
            !config.enabled -> AssistStatus.OFF
            !config.foreground -> AssistStatus.PAUSED
            else -> AssistStatus.LOADING
        }, statusText = null, lastInferenceMs = null) }
        sessionJob = scope.launch {
            sessionMutex.withLock {
                var runtime: SmolVlmRuntime? = null
                var frames: CompositionFrameSource? = null
                try {
                    val model = requireNotNull(EdgeModelCatalog.find(config.id))
                    val installed = withContext(Dispatchers.IO) {
                        // A bundled model is imported once into the same private store as
                        // an explicit download. APKs without assets keep the old path.
                        // Do not copy a 174 MB asset while the optional assistant is off
                        // or the app is backgrounded; import it on the first active use.
                        store.installed(model) ?: if (config.enabled && config.foreground) {
                            store.installBundled(model)
                        } else null
                    }
                    currentCoroutineContext().ensureActive()
                    // A running download owns its install state; a resume must not overwrite its progress.
                    if (_state.value.install !in listOf(ModelInstallStatus.PREPARING, ModelInstallStatus.DOWNLOADING,
                            ModelInstallStatus.REMOVING, ModelInstallStatus.AVAILABLE)) {
                        _state.update { it.copy(install = if (installed == null) ModelInstallStatus.ABSENT else ModelInstallStatus.INSTALLED,
                            bytes = installed?.plan?.totalBytes ?: it.bytes) }
                    }
                    if (!config.enabled || !config.foreground) return@withLock
                    if (installed == null) {
                        _state.update { it.copy(status = AssistStatus.UNINSTALLED) }
                        return@withLock
                    }
                    withContext(Dispatchers.IO) { runtime = SmolVlmRuntime(installed) }
                    currentCoroutineContext().ensureActive()
                    val engine = requireNotNull(runtime)
                    frames = CompositionFrameSource(minimumIntervalMs = 1_200L, maximumEdge = 384,
                        onError = { error ->
                            if (isCurrentSession()) _state.update { it.copy(statusText = error.message ?: "无法分析当前画面") }
                        }, onFrame = frameCallback@{ frame ->
                        val ticket = resultGate.ticket(frame.receivedAtElapsedRealtimeMs) ?: return@frameCallback
                        // The gate keeps only the latest waiting frame. A frame may still
                        // have waited longer than the result window while a previous model
                        // call was running; skip it before touching ONNX Runtime instead
                        // of spending another full inference only to discard the answer.
                        if (!resultGate.isFreshForInference(ticket, SystemClock.elapsedRealtime()) ||
                            !frame.isCurrent || !isCurrentSession()) return@frameCallback
                        val started = SystemClock.elapsedRealtime()
                        try {
                            // Once this budget expires the result gate would reject the
                            // answer anyway. Cancelling the suspend call also terminates
                            // ONNX Runtime through SmolVlmRuntime's cancellation hook,
                            // keeping a slow device from burning CPU on stale output.
                            val budgetMs = resultGate.remainingMs(ticket, started)
                                .coerceAtMost(4_750L)
                            if (budgetMs < 250L) return@frameCallback
                            val suggestion = withTimeoutOrNull(budgetMs) {
                                inferenceMutex.withLock { engine.suggest(frame.bitmap) }
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (!isCurrentSession() || !frame.isCurrent) return@frameCallback
                            val fresh = resultGate.accepts(ticket, now, frame.isCurrent)
                            _state.update { it.copy(lastInferenceMs = now - started, statusText = when {
                                !fresh -> "分析结果已过期，等待新画面"
                                suggestion == null -> "当前画面未返回可靠构图建议"
                                else -> null
                            }) }
                            if (fresh && suggestion != null) {
                                val answer = CompositionOverlay(suggestion, resultGate.expiresAt(ticket))
                                _overlay.value = answer
                                expiryJob?.cancel()
                                expiryJob = scope.launch {
                                    delay((answer.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                                    if (_overlay.value === answer) _overlay.value = null
                                }
                            } else _overlay.value = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (isCurrentSession()) {
                                _state.update { it.copy(status = AssistStatus.FAILED, statusText = error.message ?: "本地推理失败") }
                                _overlay.value = null
                                // Cancelling the session releases the model and detaches analysis; normal capture continues.
                                sessionJob?.cancel()
                            }
                        }
                    })
                    _source.value = frames
                    _state.update { it.copy(status = AssistStatus.READY, statusText = "等待画面建议") }
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    if (isCurrentSession()) _state.update { it.copy(status = AssistStatus.FAILED,
                        statusText = error.message?.take(180) ?: "模型无法在当前设备运行") }
                } finally {
                    if (_source.value === frames) _source.value = null
                    retireSource(frames)
                    withContext(NonCancellable + Dispatchers.IO) {
                        inferenceMutex.withLock { runCatching { runtime?.close() } }
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        sessionGeneration.incrementAndGet()
        installJob?.cancel()
        sessionJob?.cancel()
        val oldSource = _source.value
        _source.value = null
        retireSource(oldSource)
        invalidateView()
    }
}
