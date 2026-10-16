package dev.streetsnap.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.annotation.RequiresPermission
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState as CameraXState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * UI-independent rear-camera session. All camera commands run on main, while capture completion
 * is suspended rather than blocked. One outstanding still prevents an unbounded photo queue.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(context: Context) {
    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val mutableState = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = mutableState.asStateFlow()

    private val bindMutex = Mutex()
    private val controlMutex = Mutex()
    private val captureGate = Mutex()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var stateObserver: Observer<CameraXState>? = null
    private var cameraOpen = false
    private var settingsApplied = false
    private var focusGeneration = 0L
    @Volatile private var closed = false

    private companion object {
        // Camera2 controls should acknowledge promptly; a stuck HAL must not disable every
        // control until the activity is recreated.
        const val CAMERA_CONTROL_TIMEOUT_MS = 2_000L
    }

    @RequiresPermission(android.Manifest.permission.CAMERA)
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        effect: CameraEffect? = null,
        viewPort: ViewPort? = null,
        targetRotation: Int = Surface.ROTATION_0,
    ): CameraCapabilities = withContext(Dispatchers.Main.immediate) {
        bindMutex.withLock {
            controlMutex.withLock {
                check(!closed) { "相机控制器已经关闭。" }
                detachSession()
                mutableState.update { it.copy(isBound = false, isReady = false, error = null) }
                try {
                    val cameraProvider = ProcessCameraProvider.getInstance(appContext)
                        .awaitValue(cancelOnCancellation = false)
                    check(!closed) { "相机控制器已经关闭。" }
                    provider = cameraProvider
                    val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(cameraProvider.availableCameraInfos)
                        .firstOrNull() ?: error("没有可用的后置相机。")
                    val sensor = CameraCapabilityReader.selectSensor(appContext, info)
                    val previewBuilder = Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .setResolutionSelector(resolutionSelector(Size(1440, 1080), 2_800_000L))
                    val captureBuilder = ImageCapture.Builder()
                        .setTargetRotation(targetRotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(95)
                        .setResolutionSelector(resolutionSelector(Size(4000, 3000), 12_000_000L))
                    sensor.physicalCameraId?.let { physicalId ->
                        Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalId)
                        Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalId)
                    }
                    val newPreview = previewBuilder.build().also { it.setSurfaceProvider(surfaceProvider) }
                    val newCapture = captureBuilder.build()
                    val sensorRotation = info.getSensorRotationDegrees(targetRotation)
                    val defaultViewAspect = if (sensorRotation == 90 || sensorRotation == 270) {
                        Rational(3, 4)
                    } else Rational(4, 3)
                    val group = UseCaseGroup.Builder()
                        .setViewPort(viewPort ?: ViewPort.Builder(defaultViewAspect, targetRotation)
                            .setScaleType(ViewPort.FILL_CENTER).build())
                        .addUseCase(newPreview)
                        .addUseCase(newCapture)
                        .apply { effect?.let { addEffect(it) } }
                        .build()
                    val boundCamera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group,
                    )
                    camera = boundCamera
                    preview = newPreview
                    imageCapture = newCapture
                    val capabilities = CameraCapabilityReader.read(boundCamera.cameraInfo, sensor)
                    mutableState.update { it.copy(isBound = true, capabilities = capabilities, error = null) }
                    val observer = Observer<CameraXState> { current ->
                        cameraOpen = current.type == CameraXState.Type.OPEN
                        mutableState.update {
                            it.copy(
                                isReady = cameraOpen && settingsApplied,
                                error = current.error?.let { error -> "相机会话错误（${error.code}），请重试。" },
                            )
                        }
                    }
                    stateObserver = observer
                    boundCamera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                    applySettings(sanitizeRestoredSettings(mutableState.value.settings, capabilities))
                    capabilities
                } catch (error: Exception) {
                    detachSession()
                    mutableState.update { it.copy(isBound = false, isReady = false, error = error.message) }
                    throw error
                }
            }
        }
    }

    /** Pass PreviewView.meteringPointFactory.createPoint(x, y) for transformed screen coordinates. */
    suspend fun focus(point: MeteringPoint, lock: Boolean = false): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val (future, generation) = controlMutex.withLock {
                val current = requireCamera()
                check(mutableState.value.settings.focusDistanceDiopters == null) {
                    "请先退出手动对焦。"
                }
                val settings = mutableState.value.settings
                var meteringFlags = FocusMeteringAction.FLAG_AF
                if (settings.iso == null && !settings.autoExposureLocked) {
                    meteringFlags = meteringFlags or FocusMeteringAction.FLAG_AE
                }
                if (settings.whiteBalance == WhiteBalanceMode.AUTO) {
                    meteringFlags = meteringFlags or FocusMeteringAction.FLAG_AWB
                }
                val action = FocusMeteringAction.Builder(
                    point,
                    meteringFlags,
                ).apply {
                    if (lock) disableAutoCancel() else setAutoCancelDuration(4, TimeUnit.SECONDS)
                }.build()
                check(current.cameraInfo.isFocusMeteringSupported(action)) { "当前设备不支持点击对焦。" }
                focusGeneration++
                mutableState.update { it.copy(isFocusing = true, error = null) }
                current.cameraControl.startFocusAndMetering(action) to focusGeneration
            }
            // Autofocus can take seconds in low light. It must not hold the shutter/control lock.
            try {
                future.awaitValue().isFocusSuccessful
            } finally {
                if (generation == focusGeneration) {
                    mutableState.update { it.copy(isFocusing = false) }
                }
            }
        }

    /** Normalized coordinates refer to the untransformed preview surface, not a letterboxed View. */
    suspend fun focusNormalized(x: Float, y: Float, lock: Boolean = false): Boolean {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        val currentPreview = preview ?: error("相机尚未绑定。")
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f, currentPreview).createPoint(x, y)
        return focus(point, lock)
    }

    suspend fun setFocalLength(focalLength: FocalLength) = mutateSettings {
        it.copy(focalLength = focalLength)
    }

    suspend fun setExposureCompensation(index: Int) = mutateSettings {
        it.copy(exposureCompensationIndex = index)
    }

    suspend fun setManualExposure(iso: Int?, exposureTimeNs: Long?) = mutateSettings {
        it.copy(iso = iso, exposureTimeNs = exposureTimeNs)
    }

    suspend fun setManualFocus(distanceDiopters: Float?) = mutateSettings {
        it.copy(focusDistanceDiopters = distanceDiopters)
    }

    suspend fun setAutoExposureLock(locked: Boolean) = mutateSettings {
        it.copy(autoExposureLocked = locked)
    }

    suspend fun setWhiteBalance(mode: WhiteBalanceMode) = mutateSettings {
        it.copy(whiteBalance = mode)
    }

    /** Before bind, stores a Snap preset; after bind, validates and waits for camera acceptance. */
    suspend fun updateSettings(settings: CaptureSettings): Unit = withContext(Dispatchers.Main.immediate) {
        controlMutex.withLock {
            check(!closed) { "相机控制器已经关闭。" }
            if (camera == null) {
                mutableState.update { it.copy(settings = settings) }
            } else {
                applySettings(settings)
            }
        }
    }

    /** Fails immediately when a still is pending. The app owns publication and JPEG cleanup. */
    suspend fun capture(settings: CaptureSettings? = null): CaptureResult =
        withContext(Dispatchers.Main.immediate) {
            check(captureGate.tryLock()) { "上一张照片仍在处理中。" }
            try {
                controlMutex.withLock {
                    requireCamera()
                    check(mutableState.value.isReady) { "相机尚未进入可拍状态。" }
                    settings?.let { applySettings(it) }
                    val useCase = imageCapture ?: error("拍照用例尚未绑定。")
                    val capabilities = mutableState.value.capabilities ?: error("相机能力尚未读取。")
                    val appliedSettings = mutableState.value.settings
                    val requestedAt = SystemClock.elapsedRealtime()
                    val directory = File(appContext.cacheDir, "camera-captures")
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建拍照缓存。" }
                    val output = File.createTempFile("street-snap-", ".jpg", directory)
                    mutableState.update { it.copy(isCapturing = true, error = null) }
                    try {
                        takePicture(useCase, output)
                        CaptureResult(
                            jpegFile = output,
                            settings = appliedSettings,
                            capabilities = capabilities,
                            requestedAtElapsedRealtimeMs = requestedAt,
                            completedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        )
                    } catch (error: Exception) {
                        output.delete()
                        mutableState.update { it.copy(error = error.message) }
                        throw error
                    } finally {
                        mutableState.update { it.copy(isCapturing = false) }
                    }
                }
            } finally {
                captureGate.unlock()
            }
        }

    /** Keep photo orientation current when a folded display or device rotation changes. */
    fun updateTargetRotation(rotation: Int) {
        require(rotation in Surface.ROTATION_0..Surface.ROTATION_270)
        mainExecutor.execute {
            preview?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
        }
    }

    /** Releases only this controller's use cases; it never unbinds another component's camera. */
    fun close() {
        closed = true
        mainExecutor.execute {
            detachSession()
            mutableState.update { it.copy(isBound = false, isReady = false, isFocusing = false) }
        }
    }

    private suspend fun mutateSettings(transform: (CaptureSettings) -> CaptureSettings) =
        withContext(Dispatchers.Main.immediate) {
            controlMutex.withLock { applySettings(transform(mutableState.value.settings)) }
        }

    private suspend fun applySettings(settings: CaptureSettings) {
        val current = requireCamera()
        val caps = mutableState.value.capabilities ?: error("相机能力尚未读取。")
        validateSettings(settings, caps)
        val oldSettings = mutableState.value.settings
        val needsAllSettings = !settingsApplied
        val zoomRatio = settings.focalLength.equivalentMm?.let {
            FocalLengthMath.zoomRatio(it, caps.nativeEquivalentFocalLengthMm, caps.maxZoomRatio)
                ?: error("当前主摄不支持 ${settings.focalLength.label} 视角。")
        } ?: 1f
        val manualExposure = settings.iso != null && settings.exposureTimeNs != null
        val manualFocus = settings.focusDistanceDiopters != null
        val requests = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, settings.whiteBalance.camera2Mode)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, if (manualExposure) {
                CaptureRequest.CONTROL_AE_MODE_OFF
            } else CaptureRequest.CONTROL_AE_MODE_ON)
            .apply {
                if (manualExposure) {
                    setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, requireNotNull(settings.iso))
                    setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, requireNotNull(settings.exposureTimeNs))
                }
                if (caps.supportsAutoExposureLock) {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, settings.autoExposureLocked && !manualExposure)
                }
                if (manualFocus) {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, requireNotNull(settings.focusDistanceDiopters))
                }
            }.build()
        settingsApplied = false
        mutableState.update { it.copy(isReady = false, error = null) }
        try {
            val requestOptionsChanged = needsAllSettings ||
                settings.iso != oldSettings.iso || settings.exposureTimeNs != oldSettings.exposureTimeNs ||
                settings.focusDistanceDiopters != oldSettings.focusDistanceDiopters ||
                settings.autoExposureLocked != oldSettings.autoExposureLocked ||
                settings.whiteBalance != oldSettings.whiteBalance
            if (requestOptionsChanged) {
                // Cancelling metering is only needed when entering manual focus. Repeating it
                // for unrelated changes (for example WB) can wait forever on some camera HALs.
                if (manualFocus && oldSettings.focusDistanceDiopters == null) {
                    current.cameraControl.cancelFocusAndMetering().awaitValue(CAMERA_CONTROL_TIMEOUT_MS)
                }
                Camera2CameraControl.from(current.cameraControl)
                    .setCaptureRequestOptions(requests)
                    .awaitValue(CAMERA_CONTROL_TIMEOUT_MS)
            }
            if (needsAllSettings || oldSettings.focalLength != settings.focalLength) {
                current.cameraControl.setZoomRatio(zoomRatio).awaitValue(CAMERA_CONTROL_TIMEOUT_MS)
            }
            if (!manualExposure && caps.exposureCompensationMin != caps.exposureCompensationMax &&
                (needsAllSettings || oldSettings.iso != null ||
                    oldSettings.exposureCompensationIndex != settings.exposureCompensationIndex)) {
                current.cameraControl.setExposureCompensationIndex(settings.exposureCompensationIndex)
                    .awaitValue(CAMERA_CONTROL_TIMEOUT_MS)
            }
            settingsApplied = true
            mutableState.update { it.copy(settings = settings, zoomRatio = zoomRatio, isReady = cameraOpen, error = null) }
        } catch (error: Exception) {
            settingsApplied = needsAllSettings.not() && cameraOpen
            val message = when (error) {
                is TimeoutCancellationException -> "参数响应超时，请重试。"
                is CancellationException -> null
                else -> "参数未生效：${error.message}"
            }
            mutableState.update {
                it.copy(
                    settings = if (settingsApplied) oldSettings else it.settings,
                    isReady = cameraOpen && settingsApplied,
                    error = message,
                )
            }
            throw error
        }
    }

    private fun requireCamera(): Camera {
        check(!closed) { "相机控制器已经关闭。" }
        return camera ?: error("相机尚未绑定。")
    }

    private fun detachSession() {
        stateObserver?.let { observer -> camera?.cameraInfo?.cameraState?.removeObserver(observer) }
        stateObserver = null
        val useCases = listOfNotNull(preview, imageCapture)
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        preview?.setSurfaceProvider(null)
        camera = null
        preview = null
        imageCapture = null
        cameraOpen = false
        settingsApplied = false
        focusGeneration++
        mutableState.update { it.copy(isFocusing = false) }
    }

    private suspend fun takePicture(useCase: ImageCapture, output: File) =
        suspendCancellableCoroutine { continuation ->
            useCase.takePicture(
                ImageCapture.OutputFileOptions.Builder(output).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(Unit) else output.delete()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        output.delete()
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                },
            )
        }

    private suspend fun <T> ListenableFuture<T>.awaitValue(
        timeoutMs: Long? = null,
        cancelOnCancellation: Boolean = true,
    ): T {
        suspend fun awaitUnbounded(): T = suspendCancellableCoroutine { continuation ->
            addListener({
                try {
                    val value = get()
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error.cause ?: error)
                }
            }, mainExecutor)
            if (cancelOnCancellation) continuation.invokeOnCancellation { cancel(false) }
        }
        return if (timeoutMs == null) awaitUnbounded() else withTimeout(timeoutMs) { awaitUnbounded() }
    }

    private fun resolutionSelector(preferred: Size, maxPixels: Long): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(preferred, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .setResolutionFilter { sizes, _ ->
                sizes.filter { it.width.toLong() * it.height <= maxPixels }
                    .ifEmpty { listOf(sizes.minBy { it.width.toLong() * it.height }) }
            }.build()

    private fun sanitizeRestoredSettings(settings: CaptureSettings, caps: CameraCapabilities): CaptureSettings {
        val manualExposureValid = caps.supportsManualExposure && settings.iso != null && settings.exposureTimeNs != null &&
            settings.iso in requireNotNull(caps.isoMin)..requireNotNull(caps.isoMax) &&
            settings.exposureTimeNs in requireNotNull(caps.exposureTimeMinNs)..requireNotNull(caps.exposureTimeMaxNs)
        val focus = settings.focusDistanceDiopters?.takeIf {
            caps.supportsManualFocus && it.isFinite() && it in 0f..requireNotNull(caps.minimumFocusDistanceDiopters)
        }
        return settings.copy(
            focalLength = settings.focalLength.takeIf { it in caps.focalLengthOptions } ?: caps.focalLengthOptions.first(),
            exposureCompensationIndex = settings.exposureCompensationIndex.coerceIn(caps.exposureCompensationMin, caps.exposureCompensationMax),
            iso = settings.iso.takeIf { manualExposureValid },
            exposureTimeNs = settings.exposureTimeNs.takeIf { manualExposureValid },
            focusDistanceDiopters = focus,
            autoExposureLocked = settings.autoExposureLocked && caps.supportsAutoExposureLock,
            whiteBalance = settings.whiteBalance.takeIf { it in caps.whiteBalanceModes }
                ?: WhiteBalanceMode.AUTO,
        )
    }

    private fun validateSettings(settings: CaptureSettings, caps: CameraCapabilities) {
        require(settings.focalLength in caps.focalLengthOptions) { "当前主摄不支持 ${settings.focalLength.label} 视角。" }
        require(settings.exposureCompensationIndex in caps.exposureCompensationMin..caps.exposureCompensationMax) {
            "曝光补偿超出设备范围。"
        }
        require((settings.iso == null) == (settings.exposureTimeNs == null)) { "手动曝光需要同时指定 ISO 和快门。" }
        if (settings.iso != null && settings.exposureTimeNs != null) {
            require(caps.supportsManualExposure) { "当前设备不支持手动曝光。" }
            require(settings.iso in requireNotNull(caps.isoMin)..requireNotNull(caps.isoMax)) { "ISO 超出设备范围。" }
            require(settings.exposureTimeNs in requireNotNull(caps.exposureTimeMinNs)..requireNotNull(caps.exposureTimeMaxNs)) {
                "快门时间超出设备范围。"
            }
        }
        settings.focusDistanceDiopters?.let { distance ->
            require(caps.supportsManualFocus) { "当前设备不支持手动对焦。" }
            require(distance.isFinite() && distance in 0f..requireNotNull(caps.minimumFocusDistanceDiopters)) {
                "对焦屈光度超出设备范围。"
            }
        }
        require(!settings.autoExposureLocked || caps.supportsAutoExposureLock) { "当前设备不支持曝光锁定。" }
        require(settings.whiteBalance in caps.whiteBalanceModes) { "当前设备不支持${settings.whiteBalance.displayName}白平衡。" }
    }
}
