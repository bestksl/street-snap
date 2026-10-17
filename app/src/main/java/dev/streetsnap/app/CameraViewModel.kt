package dev.streetsnap.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.camera.core.MeteringPoint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.streetsnap.app.edge.EdgeModelCatalog
import dev.streetsnap.camera.CameraController
import dev.streetsnap.camera.ExposureMode
import dev.streetsnap.camera.FocalLength
import dev.streetsnap.camera.WhiteBalanceMode
import dev.streetsnap.color.ColorEngine
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.CubeLut
import dev.streetsnap.color.FilmRegistry
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CameraNotice(val text: String)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    val camera = CameraController(application)
    val colors = ColorEngine()
    private val repository = SettingsRepository(application)
    private val saver = PhotoSaver(application)
    internal val composition = CompositionAssistant(application, viewModelScope)
    private var foreground = false
    private val _settings = MutableStateFlow(AppSettings())
    val settings = _settings.asStateFlow()
    private val _inFlight = MutableStateFlow(0)
    val inFlight = _inFlight.asStateFlow()
    private val _latestPhoto = MutableStateFlow<Uri?>(null)
    val latestPhoto = _latestPhoto.asStateFlow()
    private val _lutLoading = MutableStateFlow(false)
    val lutLoading = _lutLoading.asStateFlow()
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded = _settingsLoaded.asStateFlow()
    private val _previewStreaming = MutableStateFlow(false)
    val previewStreaming = _previewStreaming.asStateFlow()
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError = _previewError.asStateFlow()
    private val _controlsRestored = MutableStateFlow(false)
    val controlsRestored = _controlsRestored.asStateFlow()
    private val noticeChannel = Channel<CameraNotice>(Channel.UNLIMITED)
    val notices = noticeChannel.receiveAsFlow()
    private val processing = Mutex()
    private var saveSettingsJob: Job? = null
    private val controlJobs = mutableMapOf<String, Job>()
    private var lut: CubeLut? = null
    private val lutFile = File(application.filesDir, "custom.cube")

    init {
        viewModelScope.launch {
            try {
                _settings.value = repository.settings.first()
                if (lutFile.isFile) {
                    lut = withContext(Dispatchers.IO) { lutFile.reader().use(CubeLut::parse) }
                    colors.setCustomLut(lut)
                    if (_settings.value.lutName == null) _settings.value = _settings.value.copy(lutName = lut?.title)
                } else if (_settings.value.preset == ColorPreset.CUSTOM.name) {
                    _settings.value = _settings.value.copy(preset = ColorPreset.ORIGINAL.name, lutName = null)
                }
                applyColor()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _settings.value = _settings.value.copy(preset = ColorPreset.ORIGINAL.name, lutName = null)
                applyColor()
                notify("读取上次配方失败，已使用原色")
            } finally {
                _settingsLoaded.value = true
                syncComposition()
            }
        }
    }

    fun onBindingStarted() { _controlsRestored.value = false; composition.invalidateView() }

    suspend fun onCameraBound() {
        _settingsLoaded.first { it }
        restoreCameraSettings()
    }

    private suspend fun restoreCameraSettings() {
        val capabilities = camera.state.value.capabilities ?: return
        val saved = _settings.value
        val focal = FocalLength.fromMm(saved.focalLength) ?: FocalLength.NATIVE
        _controlsRestored.value = false
        try {
            camera.setFocalLength(focal.takeIf { it in capabilities.focalLengthOptions }
                ?: capabilities.focalLengthOptions.first())
            camera.setExposureCompensation(saved.exposure.coerceIn(capabilities.exposureCompensationMin, capabilities.exposureCompensationMax))
            val whiteBalance = runCatching { WhiteBalanceMode.valueOf(saved.whiteBalance) }
                .getOrDefault(WhiteBalanceMode.AUTO)
                .takeIf { it in capabilities.whiteBalanceModes } ?: WhiteBalanceMode.AUTO
            camera.setWhiteBalance(whiteBalance)
            if (saved.whiteBalance != whiteBalance.name) update { it.copy(whiteBalance = whiteBalance.name) }
            if (capabilities.supportsManualFocus && saved.focus == "MANUAL") {
                camera.setManualFocus(saved.focusDiopters.coerceIn(0f, capabilities.minimumFocusDistanceDiopters ?: 0f))
            }
            if (capabilities.supportsManualExposure && saved.iso != null && saved.exposureTimeNs != null) {
                camera.setManualExposure(
                    saved.iso.coerceIn(capabilities.isoMin ?: saved.iso, capabilities.isoMax ?: saved.iso),
                    saved.exposureTimeNs.coerceIn(capabilities.exposureTimeMinNs ?: saved.exposureTimeNs, capabilities.exposureTimeMaxNs ?: saved.exposureTimeNs),
                )
            } else if (capabilities.supportsShutterPriority && saved.iso == null && saved.exposureTimeNs != null) {
                camera.setShutterPriority(saved.exposureTimeNs.coerceIn(
                    requireNotNull(capabilities.exposureTimeMinNs), requireNotNull(capabilities.exposureTimeMaxNs),
                ))
            } else {
                camera.setExposureMode(ExposureMode.PROGRAM)
            }
            val exposure = camera.state.value.settings
            update { it.copy(iso = exposure.iso, exposureTimeNs = exposure.exposureTimeNs) }
            _controlsRestored.value = true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            notify("Snap 参数恢复失败：${error.message ?: "请重试"}")
            throw error
        }
    }

    /** At most one camera request and two total captures; photo processing is serialized off the UI. */
    fun shoot() {
        // The shutter already shows the queue state; volume presses must not queue snackbars.
        if (_inFlight.value >= 2 || camera.state.value.isCapturing) return
        if (!camera.state.value.isReady || !_previewStreaming.value || _previewError.value != null ||
            !_controlsRestored.value || !_settingsLoaded.value) return
        _inFlight.value++
        val recipe = currentRecipe()
        val captureLut = lut
        val capturedAt = LocalDateTime.now()
        viewModelScope.launch {
            var captureFile: File? = null
            try {
                val captured = camera.capture()
                captureFile = captured.jpegFile
                val uri = processing.withLock { saver.save(captured, colors, recipe, captureLut, capturedAt) }
                _latestPhoto.value = uri
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify("拍摄失败：${error.message ?: "请重试"}")
            } finally {
                captureFile?.delete()
                _inFlight.value--
            }
        }
    }

    fun setFocalLength(focal: FocalLength) = cameraAction {
        composition.invalidateView()
        camera.setFocalLength(focal)
        update { it.copy(focalLength = focal.equivalentMm ?: 0) }
        composition.updateFocalLength(focal.equivalentMm ?: 28)
    }

    fun setExposure(index: Int) = latestCameraAction("exposure") {
        camera.setExposureCompensation(index)
        update { it.copy(exposure = index) }
    }

    fun setManualFocus(diopters: Float?) = latestCameraAction("focus") {
        val maximum = camera.state.value.capabilities?.minimumFocusDistanceDiopters
        val distance = if (diopters != null && maximum != null && maximum.isFinite() && maximum > 0f) {
            diopters.coerceIn(0f, maximum)
        } else diopters
        camera.setManualFocus(distance)
        update { it.copy(focus = if (distance == null) "AUTO" else "MANUAL", focusDiopters = distance ?: it.focusDiopters) }
    }

    fun setManualExposure(iso: Int?, exposureTimeNs: Long?) = latestCameraAction("manualExposure") {
        camera.setManualExposure(iso, exposureTimeNs)
        update { it.copy(iso = iso, exposureTimeNs = exposureTimeNs) }
    }

    fun setExposureMode(mode: ExposureMode) = latestCameraAction("manualExposure") {
        camera.setExposureMode(mode)
        val exposure = camera.state.value.settings
        update { it.copy(iso = exposure.iso, exposureTimeNs = exposure.exposureTimeNs) }
    }

    fun setShutterPriority(exposureTimeNs: Long) = latestCameraAction("manualExposure") {
        camera.setShutterPriority(exposureTimeNs)
        update { it.copy(iso = null, exposureTimeNs = exposureTimeNs) }
    }

    fun resetShootingParameters() {
        // A deferred slider or focus request must not reapply the previous value after Reset.
        listOf("exposure", "manualExposure", "focus", "tapFocus").forEach { controlJobs[it]?.cancel() }
        latestCameraAction("manualExposure") {
            val capture = camera.resetShootingParameters()
            update { it.copy(
                iso = capture.iso, exposureTimeNs = capture.exposureTimeNs,
                exposure = capture.exposureCompensationIndex,
                focus = if (capture.focusDistanceDiopters == null) "AUTO" else "MANUAL",
                focusDiopters = capture.focusDistanceDiopters ?: 0f,
            ) }
        }
    }

    fun setWhiteBalance(mode: WhiteBalanceMode) = latestCameraAction("whiteBalance") {
        camera.setWhiteBalance(mode)
        update { it.copy(whiteBalance = mode.name) }
    }

    fun setWhiteBalanceShift(amber: Int? = null, green: Int? = null) {
        update { it.copy(
            whiteBalanceAmber = (amber ?: it.whiteBalanceAmber).coerceIn(-9, 9),
            whiteBalanceGreen = (green ?: it.whiteBalanceGreen).coerceIn(-9, 9),
        ) }
        applyColor()
    }

    fun resetWhiteBalanceShift() = setWhiteBalanceShift(amber = 0, green = 0)

    fun toggleHdf() {
        update { it.copy(hdfEnabled = !it.hdfEnabled) }
        applyColor()
    }

    fun setHdfStrength(strength: Float) {
        update { it.copy(hdfStrength = strength.coerceIn(0f, 1f)) }
        applyColor()
    }

    fun focus(point: MeteringPoint, lock: Boolean = false) = latestCameraAction("tapFocus") { camera.focus(point, lock) }

    fun autoFocus() = latestCameraAction("focus") {
        if (camera.state.value.settings.focusDistanceDiopters != null) camera.setManualFocus(null)
        else camera.cancelFocus()
        update { it.copy(focus = "AUTO") }
    }

    fun toggleFocusMode() = latestCameraAction("focus") {
        val distance = camera.toggleFocusMode(_settings.value.focusDiopters)
        update { it.copy(focus = if (distance == null) "AUTO" else "MANUAL",
            focusDiopters = distance ?: it.focusDiopters) }
    }
    fun toggleGrid() = update { it.copy(grid = !it.grid) }
    fun toggleLevel() = update { it.copy(level = !it.level) }

    fun setPreset(preset: ColorPreset) {
        if (preset == ColorPreset.CUSTOM && lut == null) {
            notify("请先导入 .cube LUT")
            return
        }
        update { it.copy(preset = preset.name, filmId = null) }
        applyColor()
    }

    fun setFilm(id: String?) {
        val profile = FilmRegistry.find(id)
        update {
            if (profile == null) it.copy(filmId = null)
            else it.copy(filmId = profile.id)
        }
        applyColor()
    }

    fun setMascotCamera(id: String?) = update { it.copy(mascotCamera = CameraPreferences.mascot(id)) }
    fun setCaptureOrientation(id: String) = update { it.copy(captureOrientation = CameraPreferences.orientation(id)) }

    fun setCompositionEnabled(enabled: Boolean) = update { it.copy(compositionEnabled = enabled) }
    fun setCompositionRealtime(enabled: Boolean) = update {
        it.copy(compositionRealtime = enabled, compositionEnabled = true)
    }
    fun requestComposition() {
        if (!_settings.value.compositionEnabled) setCompositionEnabled(true)
        composition.requestAdvice()
    }
    fun setCompositionModel(id: String) {
        if (EdgeModelCatalog.find(id) != null) update { it.copy(compositionModel = id) }
    }
    fun onForegroundChanged(active: Boolean) {
        foreground = active
        if (_settingsLoaded.value) syncComposition()
    }
    private fun syncComposition() = composition.configure(
        _settings.value.compositionModel, _settings.value.compositionEnabled, foreground,
        realtime = _settings.value.compositionRealtime,
        focalLengthMm = _settings.value.focalLength.takeIf { it > 0 } ?: 28)

    fun setColor(
        intensity: Float? = null, grain: Float? = null, vignette: Float? = null,
        saturation: Float? = null, hue: Float? = null, contrast: Float? = null,
        brightness: Float? = null, highlights: Float? = null, shadows: Float? = null,
        sharpness: Float? = null, clarity: Float? = null,
    ) {
        update { settings -> settings.updateLook { look -> look.copy(
            intensity = intensity ?: look.intensity, grain = grain ?: look.grain, vignette = vignette ?: look.vignette,
            saturation = saturation ?: look.saturation, hue = hue ?: look.hue, contrast = contrast ?: look.contrast,
            brightness = brightness ?: look.brightness, highlights = highlights ?: look.highlights,
            shadows = shadows ?: look.shadows, sharpness = sharpness ?: look.sharpness, clarity = clarity ?: look.clarity,
        ) } }
        applyColor()
    }

    fun resetCurrentLook() {
        update { it.resetLook() }
        applyColor()
    }

    fun importLut(uri: Uri) {
        if (_lutLoading.value) return
        _lutLoading.value = true
        viewModelScope.launch {
            val temporary = File(getApplication<Application>().filesDir, "custom-import.cube")
            try {
                val result = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().use { output ->
                            val bytes = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(bytes)
                                if (count < 0) break
                                total += count
                                require(total <= 4L * 1024 * 1024) { "LUT 文件不能超过 4 MiB" }
                                output.write(bytes, 0, count)
                            }
                        }
                    } ?: error("无法打开 LUT 文件")
                    val parsed = temporary.reader().use(CubeLut::parse)
                    check(temporary.renameTo(lutFile)) { "无法保存 LUT" }
                    val fileName = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull()
                    parsed to (fileName ?: parsed.title)
                }
                lut = result.first
                colors.setCustomLut(lut)
                update { it.copy(preset = ColorPreset.CUSTOM.name, filmId = null, lutName = result.second.take(120)) }
                applyColor()
                notify("已导入 ${result.second}")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify("LUT 导入失败：${error.message ?: "文件格式无效"}")
            } finally {
                temporary.delete()
                _lutLoading.value = false
            }
        }
    }

    fun notify(text: String) { noticeChannel.trySend(CameraNotice(text)) }
    fun onPreviewStreaming(streaming: Boolean) { _previewStreaming.value = streaming }
    fun onPreviewError(error: Throwable) { _previewError.value = "影调取景失败：${error.message ?: "GPU 暂不可用"}" }
    fun clearPreviewError() { _previewError.value = null }

    private fun currentRecipe() = _settings.value.colorRecipe()

    private fun applyColor() { colors.updateRecipe(currentRecipe()) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
        if (_settingsLoaded.value) syncComposition()
        saveSettingsJob?.cancel()
        saveSettingsJob = viewModelScope.launch {
            delay(180)
            try { repository.save(_settings.value) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                notify("参数未能记住：${error.message ?: "存储失败"}")
            }
        }
    }

    private fun cameraAction(action: suspend () -> Unit) { viewModelScope.launch { runCamera(action) } }
    private fun latestCameraAction(key: String, action: suspend () -> Unit) {
        controlJobs[key]?.cancel()
        controlJobs[key] = viewModelScope.launch {
            delay(45)
            runCamera(action)
        }
    }
    private suspend fun runCamera(action: suspend () -> Unit) {
        try { action() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            notify(error.message ?: "相机参数调整失败")
        }
    }

    override fun onCleared() {
        composition.close()
        camera.close()
        colors.close()
        noticeChannel.close()
    }
}
