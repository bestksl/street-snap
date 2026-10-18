package dev.streetsnap.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.camera.core.MeteringPoint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.streetsnap.app.composition.AdviceSessionBudget
import dev.streetsnap.app.composition.ExposureAdviser
import dev.streetsnap.app.composition.ExposureIssue
import dev.streetsnap.app.composition.LookAdviser
import dev.streetsnap.app.composition.LookTarget
import dev.streetsnap.app.edge.EdgeModelCatalog
import dev.streetsnap.camera.CameraController
import dev.streetsnap.camera.ExposureMode
import dev.streetsnap.camera.LumaMeter
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

/** Realtime look-suggestion card. [trying] = the suggested look is applied as a preview. */
data class LookAdviceCard(
    val key: String,
    val label: String,
    val reason: String,
    val trying: Boolean = false,
)

/** Exposure hint card. The action text (EV stop / HDR suggestion) is formatted here. */
data class ExposureAdviceCard(
    val message: String,
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    val camera = CameraController(application)
    val colors = ColorEngine()
    private val repository = SettingsRepository(application)
    private val saver = PhotoSaver(application)
    internal val composition = CompositionAssistant(application, viewModelScope)
    private val adviceBudget = AdviceSessionBudget()
    private val lookAdviser = LookAdviser(budget = adviceBudget)
    private val exposureAdviser = ExposureAdviser(budget = adviceBudget)
    private val _lookAdviceCard = MutableStateFlow<LookAdviceCard?>(null)
    val lookAdviceCard = _lookAdviceCard.asStateFlow()
    private val _exposureAdviceCard = MutableStateFlow<ExposureAdviceCard?>(null)
    val exposureAdviceCard = _exposureAdviceCard.asStateFlow()
    private var pendingLookTarget: LookTarget? = null
    private var trialLook: TrialLook? = null
    private data class TrialLook(val preset: String, val filmId: String?)
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
    private val _histogram = MutableStateFlow<IntArray?>(null)
    val histogram = _histogram.asStateFlow()
    /** Real luma histogram from analysis frames; the same sample feeds the exposure adviser. */
    internal val lumaMeter = LumaMeter { sample ->
        _histogram.value = sample.counts
        onMeterSample(sample.stats)
    }

    private fun onMeterSample(stats: dev.streetsnap.camera.ExposureStats) {
        if (!_settingsLoaded.value || !foreground || !_settings.value.exposureAdviceEnabled) return
        val capture = camera.state.value.settings
        val caps = camera.state.value.capabilities
        val step = caps?.exposureCompensationStepEv ?: 0.0
        val advice = exposureAdviser.onFrame(
            stats,
            // EV compensation does nothing in manual mode; never suggest a control that is inert.
            evAdjustable = capture.exposureMode != ExposureMode.MANUAL && step > 0.0,
            canLowerEv = capture.exposureCompensationIndex > (caps?.exposureCompensationMin ?: 0),
            canRaiseEv = capture.exposureCompensationIndex < (caps?.exposureCompensationMax ?: 0),
        ) ?: return
        val nextEv = (capture.exposureCompensationIndex + advice.issue.evDirection) * step
        _exposureAdviceCard.value = ExposureAdviceCard(
            when (advice.issue) {
                ExposureIssue.WIDE_DYNAMIC_RANGE -> "明暗光比大，可开 HDR 或避开强光"
                ExposureIssue.HIGHLIGHTS_CLIPPED -> "高光过亮，建议 EV %+.1f".format(nextEv)
                ExposureIssue.SHADOWS_CLIPPED -> "暗部太黑，建议 EV %+.1f".format(nextEv)
            },
        )
    }
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
                syncMeter()
            }
        }
    }

    init {
        composition.sceneChangeListener = {
            // Same scene-change contract as composition advice: pending streak and card die
            // with the old scene; session history survives so the same card cannot bounce back.
            lookAdviser.onSceneChanged()
            if (trialLook == null) _lookAdviceCard.value = null
        }
        viewModelScope.launch {
            composition.sceneReading.collect { reading ->
                if (!_settingsLoaded.value) return@collect
                val settings = _settings.value
                if (!settings.lookAdviceEnabled || !foreground) return@collect
                val advice = lookAdviser.onFrame(reading, settings.activeLookKey, settings.lookDismissedAt)
                if (advice != null) {
                    pendingLookTarget = advice.target
                    if (trialLook == null) {
                        _lookAdviceCard.value = LookAdviceCard(advice.key, advice.target.label, advice.target.reason)
                    }
                }
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

    fun toggleHdr() {
        update { it.copy(hdrEnabled = !it.hdrEnabled) }
        applyColor()
    }

    fun setHdrStrength(strength: Float) {
        update { it.copy(hdrStrength = strength.coerceIn(0f, 1f)) }
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

    /** Viewfinder corner button: OFF -> histogram -> histogram + exposure readout -> OFF. */
    fun cycleViewfinderOsd() = update { it.copy(viewfinderOsd = ViewfinderOsd.next(it.viewfinderOsd)) }

    fun setPreset(preset: ColorPreset) {
        if (preset == ColorPreset.CUSTOM && lut == null) {
            notify("请先导入 .cube LUT")
            return
        }
        applyPreset(preset)
        onUserPickedLook()
    }

    private fun applyPreset(preset: ColorPreset) {
        update { it.copy(preset = preset.name, filmId = null) }
        applyColor()
    }

    fun setFilm(id: String?) {
        applyFilm(id)
        onUserPickedLook()
    }

    private fun applyFilm(id: String?) {
        val profile = FilmRegistry.find(id)
        update {
            if (profile == null) it.copy(filmId = null)
            else it.copy(filmId = profile.id)
        }
        applyColor()
    }

    /**
     * Manual look choices silence realtime suggestions for the rest of the foreground session
     * (Fujifilm rule: picking a film simulation by hand ends AUTO), and clear any active trial.
     */
    private fun onUserPickedLook() {
        val wasTrying = trialLook != null
        trialLook = null
        _lookAdviceCard.value = null
        lookAdviser.onUserPickedLook()
        if (wasTrying || _settings.value.lookAdviceDismissStreak != 0) {
            update { it.copy(lookAdviceDismissStreak = 0) }
        }
    }

    /** Independent switch in shooting settings. Re-enabling starts a fresh nudge session. */
    fun setLookAdviceEnabled(enabled: Boolean) {
        update { it.copy(
            lookAdviceEnabled = enabled,
            lookAdviceDismissStreak = if (enabled) 0 else it.lookAdviceDismissStreak,
        ) }
        if (enabled) {
            lookAdviser.resetSession()
        } else {
            revertTrialLook()
            _lookAdviceCard.value = null
        }
    }

    /** Exposure hints have their own switch; turning them off also drops the visible card. */
    fun setExposureAdviceEnabled(enabled: Boolean) {
        update { it.copy(exposureAdviceEnabled = enabled) }
        if (!enabled) {
            exposureAdviser.resetSession()
            _exposureAdviceCard.value = null
        }
    }

    /** × on an exposure card: hidden for the rest of this foreground session, no persistence. */
    fun dismissExposureAdvice() {
        val issue = exposureAdviser.visibleIssue ?: run {
            _exposureAdviceCard.value = null
            return
        }
        exposureAdviser.dismiss(issue)
        _exposureAdviceCard.value = null
    }

    /** Apply the suggested look as a WYSIWYG preview; the previous look is kept for revert. */
    fun trySuggestedLook() {
        val target = pendingLookTarget ?: return
        val card = _lookAdviceCard.value ?: return
        if (trialLook != null || card.trying || card.key != target.key) return
        val current = _settings.value
        trialLook = TrialLook(current.preset, current.filmId)
        if (target.filmId != null) applyFilm(target.filmId) else applyPreset(target.preset)
        lookAdviser.markConsumed(target.key)
        update { it.copy(lookAdviceDismissStreak = 0) }
        _lookAdviceCard.value = card.copy(trying = true)
    }

    /** Keep the trialled look: it becomes the user's own choice, so suggestions fall silent. */
    fun keepSuggestedLook() {
        if (trialLook == null) return
        trialLook = null
        lookAdviser.onUserPickedLook()
        update { it.copy(lookAdviceDismissStreak = 0) }
        _lookAdviceCard.value = null
    }

    /** Undo the trial and restore the look the user had before. */
    fun revertSuggestedLook() {
        revertTrialLook()
        _lookAdviceCard.value = null
    }

    /** × on the card: remember the key for 7 days and count a consecutive-dismiss streak. */
    fun dismissLookAdvice() {
        val card = _lookAdviceCard.value ?: return
        revertTrialLook()
        lookAdviser.markConsumed(card.key)
        var turnedOff = false
        update { settings ->
            val streak = settings.lookAdviceDismissStreak + 1
            turnedOff = streak >= DISMISS_STREAK_AUTO_OFF
            settings.copy(
                lookDismissedAt = settings.lookDismissedAt + (card.key to System.currentTimeMillis()),
                lookAdviceDismissStreak = if (turnedOff) 0 else streak,
                lookAdviceEnabled = if (turnedOff) false else settings.lookAdviceEnabled,
            )
        }
        _lookAdviceCard.value = null
        if (turnedOff) {
            lookAdviser.resetSession()
            notify("已连续忽略 $DISMISS_STREAK_AUTO_OFF 次，影调建议已关闭，可在拍摄设置中重新开启")
        }
    }

    private fun revertTrialLook() {
        val trial = trialLook ?: return
        trialLook = null
        update { it.copy(preset = trial.preset, filmId = trial.filmId) }
        applyColor()
    }

    fun setMascotCamera(id: String?) = update { it.copy(mascotCamera = CameraPreferences.nameplate(id)) }
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
        if (active) {
            // New foreground session: per-session budgets and the manual-pick silence reset.
            adviceBudget.reset()
            lookAdviser.resetSession()
            exposureAdviser.resetSession()
            trialLook = null
            _lookAdviceCard.value = null
            _exposureAdviceCard.value = null
        } else {
            // A trial is a viewfinder preview, not a committed choice; undo it on backgrounding.
            revertTrialLook()
            _lookAdviceCard.value = null
            _exposureAdviceCard.value = null
        }
        if (_settingsLoaded.value) {
            syncComposition()
            syncMeter()
        }
    }
    private fun syncComposition() = composition.configure(
        _settings.value.compositionModel, _settings.value.compositionEnabled, foreground,
        realtime = _settings.value.compositionRealtime,
        focalLengthMm = _settings.value.focalLength.takeIf { it > 0 } ?: 28,
        lookAdvice = _settings.value.lookAdviceEnabled)

    /** The analysis stream is bound for the histogram OSD or realtime exposure hints. */
    private fun syncMeter() {
        val wanted = _settingsLoaded.value && foreground && (
            _settings.value.viewfinderOsd != ViewfinderOsd.OFF ||
                _settings.value.exposureAdviceEnabled)
        lumaMeter.active = wanted
        if (!wanted) _histogram.value = null
    }

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
                onUserPickedLook()
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
        if (_settingsLoaded.value) {
            syncComposition()
            syncMeter()
        }
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

    companion object {
        /** Three consecutive × taps switch the suggestion feature off until re-enabled in settings. */
        const val DISMISS_STREAK_AUTO_OFF = 3
    }
}
