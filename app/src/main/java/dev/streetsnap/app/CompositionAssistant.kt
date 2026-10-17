package dev.streetsnap.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
internal enum class CompositionOutcome { RETURNED, NO_RESULT, TIMED_OUT, SCENE_CHANGED, FAILED }
internal data class CompositionUiState(
    val modelId: String = "smolvlm-256m",
    val install: ModelInstallStatus = ModelInstallStatus.CHECKING,
    val bytes: Long? = null,
    val downloadedBytes: Long = 0,
    val installError: String? = null,
    val status: AssistStatus = AssistStatus.OFF,
    val statusText: String? = null,
    val lastInferenceMs: Long? = null,
    val sampleIntervalMs: Long = 3_000L,
    val inferenceBudgetMs: Long = 30_000L,
    val inferenceStartedAtMs: Long? = null,
    val lastOutcome: CompositionOutcome? = null,
)
internal data class CompositionOverlay(val suggestion: CompositionSuggestion, val expiresAtMs: Long)
private data class CompletedComposition(val suggestion: CompositionSuggestion?)

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
    private val resultGate = CompositionResultGate(maximumAgeMs = 65_000L)
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
        }, statusText = null, lastInferenceMs = null,
            sampleIntervalMs = 3_000L, inferenceBudgetMs = 30_000L, inferenceStartedAtMs = null, lastOutcome = null) }
        sessionJob = scope.launch {
            sessionMutex.withLock {
                var runtime: SmolVlmRuntime? = null
                var frames: CompositionFrameSource? = null
                try {
                    val model = requireNotNull(EdgeModelCatalog.find(config.id))
                    val installed = withContext(Dispatchers.IO) {
                        // An APK may bundle faster graphs at the same model revision.
                        // Ensure that variant on active use before considering an older
                        // installed variant. Disabled/background sessions never copy assets.
                        if (config.enabled && config.foreground) {
                            store.installBundled(model) ?: store.installed(model)
                        } else store.installed(model)
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
                    val cadence = CompositionCadence()
                    frames = CompositionFrameSource(minimumIntervalMs = cadence.cooldownMs(), maximumEdge = 384,
                        onSceneChanged = {
                            // Serialize scene invalidation with result publication. A scene
                            // change just after the final check must still clear that answer.
                            scope.launch(Dispatchers.Main.immediate) {
                                if (isCurrentSession()) {
                                    _overlay.value = null
                                    expiryJob?.cancel()
                                }
                            }
                        },
                        onError = { error ->
                            scope.launch(Dispatchers.Main.immediate) {
                                if (isCurrentSession()) _state.update {
                                    it.copy(statusText = error.message ?: "无法分析当前画面")
                                }
                            }
                        }, onFrame = frameCallback@{ frame ->
                        val ticket = resultGate.ticket(frame.receivedAtElapsedRealtimeMs) ?: return@frameCallback
                        val started = SystemClock.elapsedRealtime()
                        val budgetMs = cadence.budgetMs()
                        val canStart = withContext(Dispatchers.Main.immediate) {
                            if (!isCurrentSession() || !frame.isCurrent ||
                                !resultGate.isFreshForInference(ticket, SystemClock.elapsedRealtime())) false
                            else {
                                _state.update { it.copy(inferenceStartedAtMs = started, inferenceBudgetMs = budgetMs,
                                    statusText = "正在分析，请保持画面稳定") }
                                true
                            }
                        }
                        if (!canStart) return@frameCallback
                        try {
                            // A wrapped nullable answer distinguishes completion from timeout.
                            val completed = withTimeoutOrNull(budgetMs) {
                                inferenceMutex.withLock { CompletedComposition(engine.suggest(frame.bitmap)) }
                            }
                            val inferenceMs = SystemClock.elapsedRealtime() - started
                            withContext(Dispatchers.Main.immediate) {
                                if (!isCurrentSession()) return@withContext
                                // Recheck after dispatch, alongside all state/overlay writes.
                                // configure/invalidateView and scene clearing cannot interleave
                                // a successful check and publication on the UI thread.
                                val now = SystemClock.elapsedRealtime()
                                val suggestion = completed?.suggestion
                                val fresh = resultGate.accepts(ticket, now, frame.isCurrent) && frame.isSceneCurrent
                                val outcome = when {
                                    completed == null -> CompositionOutcome.TIMED_OUT
                                    suggestion == null -> CompositionOutcome.NO_RESULT
                                    !fresh -> CompositionOutcome.SCENE_CHANGED
                                    else -> CompositionOutcome.RETURNED
                                }
                                val next = if (completed == null) cadence.recordTimeout(inferenceMs)
                                    else cadence.recordCompleted(inferenceMs, suggestion != null)
                                frames?.setMinimumIntervalMs(next.cooldownMs)
                                Log.i("StreetSnapComposition", "outcome=$outcome elapsed_ms=$inferenceMs " +
                                    "cooldown_ms=${next.cooldownMs} budget_ms=${next.nextBudgetMs}")
                                _state.update { it.copy(lastInferenceMs = inferenceMs, lastOutcome = outcome,
                                    sampleIntervalMs = next.cooldownMs, inferenceBudgetMs = next.nextBudgetMs,
                                    inferenceStartedAtMs = null, statusText = when (outcome) {
                                        CompositionOutcome.RETURNED -> "构图框已返回"
                                        CompositionOutcome.TIMED_OUT -> "本次分析超时，休息后重试"
                                        CompositionOutcome.SCENE_CHANGED -> "画面已变化，等待下一帧"
                                        else -> "已完成分析，未返回有效构图框"
                                    }) }
                                expiryJob?.cancel()
                                if (outcome == CompositionOutcome.RETURNED && suggestion != null) {
                                    // Slow analysis remains useful on an unchanged scene.
                                    // Movement clears the box via onSceneChanged.
                                    val answer = CompositionOverlay(suggestion, now + 8_000L)
                                    _overlay.value = answer
                                    expiryJob = scope.launch(Dispatchers.Main.immediate) {
                                        delay((answer.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                                        if (_overlay.value === answer) _overlay.value = null
                                    }
                                } else _overlay.value = null
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                if (isCurrentSession()) {
                                    _state.update { it.copy(status = AssistStatus.FAILED, statusText = error.message ?: "本地推理失败",
                                        inferenceStartedAtMs = null, lastOutcome = CompositionOutcome.FAILED,
                                        lastInferenceMs = SystemClock.elapsedRealtime() - started) }
                                    _overlay.value = null
                                    expiryJob?.cancel()
                                    // Cancelling the session releases the model and detaches analysis; normal capture continues.
                                    sessionJob?.cancel()
                                }
                            }
                        }
                    })
                    _source.value = frames
                    _state.update { it.copy(status = AssistStatus.READY, statusText = "等待画面建议") }
                    awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    if (isCurrentSession()) _state.update { it.copy(status = AssistStatus.FAILED,
                        statusText = error.message?.take(180) ?: "模型无法在当前设备运行", inferenceStartedAtMs = null) }
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
