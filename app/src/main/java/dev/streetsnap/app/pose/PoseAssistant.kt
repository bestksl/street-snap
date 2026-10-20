package dev.streetsnap.app.pose

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.streetsnap.app.edge.PoseModelCatalog
import dev.streetsnap.app.edge.PoseModelStore
import dev.streetsnap.camera.PoseFrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class PoseModelStatus { CHECKING, ABSENT, DOWNLOADING, INSTALLED, FAILED }

internal data class PoseUiState(
    val enabled: Boolean = false,
    /** Live pose detection running (model installed and feature in foreground). */
    val live: Boolean = false,
    /** Feature on, model absent: the figure browser works without any detection. */
    val demo: Boolean = false,
    val modelStatus: PoseModelStatus = PoseModelStatus.CHECKING,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = PoseModelCatalog.lite.bytes,
    val installError: String? = null,
    val currentPoseId: String = PoseCatalog.poses.first().id,
    val guide: PoseGuide? = null,
)

/**
 * T72 session orchestration: optional MediaPipe source + pluggable model lifecycle +
 * the pure-Kotlin coach, mirroring CompositionAssistant's degradation contract:
 * no model → demo mode (target figures browsable, no detection); model error →
 * demo mode; feature off/background → camera stream released.
 */
internal class PoseAssistant(
    context: Context,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val store = PoseModelStore(appContext)
    private val model = PoseModelCatalog.lite
    private val coach = PoseCoach()
    private val coachLock = Any()

    private val _source = MutableStateFlow<PoseFrameSource?>(null)
    val source = _source.asStateFlow()

    private val _state = MutableStateFlow(PoseUiState())
    val state = _state.asStateFlow()

    private data class Configuration(val enabled: Boolean, val foreground: Boolean)
    private var configuration: Configuration? = null
    private var detector: MediaPipePoseSource? = null
    private var restartJob: Job? = null
    private var installJob: Job? = null
    // Serialises reconfigure/start/stop so the init check and the first settings emission
    // cannot each build their own graph concurrently.
    private val reconfigureLock = Mutex()
    private var closed = false

    init {
        scope.launch {
            val status = checkInstalled()
            _state.update { it.copy(
                modelStatus = if (status != null) PoseModelStatus.INSTALLED else PoseModelStatus.ABSENT) }
            reconfigure()
        }
    }

    fun configure(enabled: Boolean, foreground: Boolean) {
        if (closed) return
        val next = Configuration(enabled, foreground)
        if (next == configuration) return
        configuration = next
        _state.update { it.copy(enabled = enabled) }
        reconfigure()
    }

    private fun reconfigure() {
        restartJob?.cancel()
        restartJob = scope.launch {
            reconfigureLock.withLock {
                if (closed) return@withLock
                val config = configuration
                val active = config?.enabled == true && config.foreground
                val installedFile = withContext(Dispatchers.IO) { store.installed(model) }
                if (closed) return@withLock
                val liveReady = if (active && installedFile != null) {
                    startLive(installedFile)
                } else {
                    stopLive()
                    false
                }
                _state.update {
                    it.copy(
                        live = active && liveReady,
                        demo = active && !liveReady,
                        currentPoseId = synchronized(coachLock) { coach.current().id },
                        guide = if (active && liveReady) it.guide else null,
                    )
                }
            }
        }
    }

    private suspend fun checkInstalled() = withContext(Dispatchers.IO) { store.installed(model) }

    /** Returns true when the live source is bound; false falls back to demo mode. */
    private suspend fun startLive(modelFile: java.io.File): Boolean {
        if (_source.value != null && detector != null) return true
        if (closed) return false
        val newDetector = runCatching {
            MediaPipePoseSource(
                appContext, modelFile,
                onResult = { sample -> acceptSample(sample) },
                onError = { error ->
                    Log.w("StreetSnapPose", "pose inference error: ${error.message}")
                },
            )
        }.getOrElse { error ->
            // Unsupported ABI / corrupted runtime: keep the feature usable in demo mode.
            Log.w("StreetSnapPose", "pose landmarker unavailable", error)
            _state.update {
                it.copy(installError = "实时识别在此设备上不可用，已切换演示模式")
            }
            return false
        }
        if (closed) {
            newDetector.close()
            return false
        }
        detector = newDetector
        synchronized(coachLock) { coach.reset() }
        val newSource = PoseFrameSource(
            onError = { error -> Log.w("StreetSnapPose", error) },
        ) { bitmap -> newDetector.detect(bitmap) }
        _source.value = newSource
        return true
    }

    private fun acceptSample(sample: PoseSample?) {
        val guide = synchronized(coachLock) {
            coach.observe(sample, SystemClock.elapsedRealtime())
        }
        _state.update { state ->
            if (!state.live) state else state.copy(
                guide = guide,
                currentPoseId = guide.definitionId,
                demo = false,
            )
        }
    }

    private fun stopLive() {
        val oldSource = _source.value
        val oldDetector = detector
        _source.value = null
        detector = null
        oldDetector?.let { value ->
            scope.launch(NonCancellable) { withContext(Dispatchers.IO) { value.close() } }
        }
        if (oldSource != null) {
            // Let CameraX observe the null source and unbind ImageAnalysis before closing its executor.
            scope.launch(NonCancellable) {
                kotlinx.coroutines.delay(300)
                withContext(Dispatchers.IO) { oldSource.close() }
            }
        }
        synchronized(coachLock) { coach.reset() }
        _state.update { it.copy(guide = null) }
    }

    fun selectPose(id: String) {
        val definition = PoseCatalog.poses.firstOrNull { it.id == id } ?: return
        synchronized(coachLock) { coach.select(definition) }
        _state.update { it.copy(currentPoseId = id, guide = null) }
    }

    fun nextPose() {
        val definition = synchronized(coachLock) { coach.next() }
        _state.update { it.copy(currentPoseId = definition.id, guide = null) }
    }

    /** Explicit in-app download action only. */
    fun install() {
        if (closed) return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.update { it.copy(modelStatus = PoseModelStatus.DOWNLOADING, installError = null,
                downloadedBytes = 0L) }
            try {
                val file = store.download(model) { downloaded, total ->
                    _state.update { it.copy(downloadedBytes = downloaded, totalBytes = total) }
                }
                _state.update { it.copy(modelStatus = PoseModelStatus.INSTALLED, installError = null) }
                reconfigure()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update {
                    it.copy(modelStatus = PoseModelStatus.FAILED,
                        installError = "姿势模型下载失败：${error.message ?: "请重试"}")
                }
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        scope.launch {
            val installed = withContext(Dispatchers.IO) { store.installed(model) }
            _state.update {
                it.copy(
                    modelStatus = if (installed != null) PoseModelStatus.INSTALLED else PoseModelStatus.ABSENT,
                    installError = null,
                )
            }
        }
    }

    fun removeModel() {
        installJob?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { store.remove(model) }
            _state.update { it.copy(modelStatus = PoseModelStatus.ABSENT) }
            // Reconfigure observes the missing file: stops live and falls back to demo.
            reconfigure()
        }
    }

    override fun close() {
        closed = true
        restartJob?.cancel()
        installJob?.cancel()
        runCatching { detector?.close() }
        _source.value?.close()
        _source.value = null
    }
}
