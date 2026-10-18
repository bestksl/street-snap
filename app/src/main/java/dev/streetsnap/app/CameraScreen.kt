@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.streetsnap.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.display.DisplayManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.streetsnap.camera.CameraCapabilities
import dev.streetsnap.camera.CameraState
import dev.streetsnap.camera.ExposureMode
import dev.streetsnap.camera.FocalLength
import dev.streetsnap.camera.WhiteBalanceMode
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(activity: MainActivity, model: CameraViewModel = viewModel()) {
    val cameraState by model.camera.state.collectAsStateWithLifecycle()
    val streaming by model.previewStreaming.collectAsStateWithLifecycle()
    val renderingError by model.previewError.collectAsStateWithLifecycle()
    val restored by model.controlsRestored.collectAsStateWithLifecycle()
    val state = cameraState.copy(isReady = cameraState.isReady && streaming && renderingError == null && restored)
    val settings by model.settings.collectAsStateWithLifecycle()
    val inFlight by model.inFlight.collectAsStateWithLifecycle()
    val latestPhoto by model.latestPhoto.collectAsStateWithLifecycle()
    val lutLoading by model.lutLoading.collectAsStateWithLifecycle()
    val loaded by model.settingsLoaded.collectAsStateWithLifecycle()
    val compositionState by model.composition.state.collectAsStateWithLifecycle()
    val compositionSource by model.composition.source.collectAsStateWithLifecycle()
    val compositionOverlay by model.composition.overlay.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(activity.hasCameraPermission()) }
    var denied by remember { mutableStateOf(false) }
    var adjustmentTab by remember { mutableStateOf<AdjustmentTab?>(null) }
    var showCapabilities by remember { mutableStateOf(false) }
    var showMascotPicker by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = adjustmentTab != null && !showCapabilities && !showMascotPicker) { adjustmentTab = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        denied = !it
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importLut)
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = activity.hasCameraPermission()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> model.onForegroundChanged(true)
                Lifecycle.Event.ON_PAUSE -> model.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        model.onForegroundChanged(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.onForegroundChanged(false) }
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(activity, loaded, settings.captureOrientation) {
        if (loaded) activity.applyCaptureOrientation(settings.captureOrientation)
    }
    LaunchedEffect(model) {
        model.notices.collect { notice ->
            snackbar.showSnackbar(notice.text, duration = SnackbarDuration.Short)
        }
    }
    val currentReady by rememberUpdatedState(permitted && state.isReady && !showCapabilities && !showMascotPicker)
    DisposableEffect(activity, model) {
        activity.volumeShutter = { trigger ->
            if (currentReady) {
                if (trigger) model.shoot()
                true
            } else false
        }
        onDispose { activity.volumeShutter = null }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!permitted) {
                PermissionPanel(denied, { permission.launch(Manifest.permission.CAMERA) }, activity::openAppSettings)
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    if (landscape && maxWidth >= 600.dp) {
                        // Large landscape screens keep the all-controls side rail; the
                        // phone portrait path below is the floating full-screen viewfinder.
                        val sideWidth = minOf(356.dp, maxWidth * .48f)
                        Row(Modifier.fillMaxSize()) {
                            Viewfinder(model, state, settings, retry, true, compositionSource, compositionOverlay, compositionState,
                                Modifier.weight(1f).fillMaxHeight(), onRetry = { retry++ })
                            ShootingControls(state, settings, inFlight, loaded, lutLoading, model,
                                Modifier.width(sideWidth).fillMaxHeight(),
                                adjustmentTab, { adjustmentTab = it }, sidePanel = true,
                                panelHeight = 264.dp,
                                importLut = { lutPicker.launch(arrayOf("*/*")) },
                                openMascotPicker = { showMascotPicker = true },
                                compositionState = compositionState,
                                latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                        }
                    } else if (maxHeight > maxWidth) {
                        // Phone portrait: a camera-back layout. Nameplate, the exact 3:4
                        // viewfinder screen and an opaque control cluster stack edge to
                        // edge, so there is no letterbox void between picture and controls.
                        // Nameplate: follow-look by default (the body tracks the active
                        // look/film family), a pinned body, or hidden entirely.
                        val hideNameplate = settings.mascotCamera == CameraPreferences.HIDDEN
                        // Tall enough for the body card to read as a camera plate rather than
                        // a flattened strip (T22: 48dp crowded the 74x40 artwork).
                        val nameplateHeight = if (hideNameplate) 0.dp else 80.dp
                        // Worst case the cluster can show: divider + paddings + one override
                        // chip row + focal row + fixed focal/shutter gap + 92dp shutter row.
                        // Reserve it up front; on short screens the 3:4 picture shrinks a
                        // touch instead of clipping.
                        val clusterMinHeight = 218.dp
                        val framedWidth = minOf(maxWidth, (maxHeight - nameplateHeight - clusterMinHeight) * 3f / 4f)
                        Column(Modifier.fillMaxSize().background(Color.Black)) {
                            if (!hideNameplate) {
                                val following = settings.mascotCamera == null
                                val activePreset = settings.colorRecipe().preset
                                val lookName = settings.filmId?.let { FilmRegistry.find(it)?.displayName }
                                    ?: activePreset.displayName
                                val familyCamera = if (following) cameraForLook(activePreset.name) else null
                                val pinnedCamera = if (following) null else CameraModel.fromId(settings.mascotCamera)
                                val nameplateCamera = familyCamera ?: pinnedCamera
                                ViewfinderNameplate(
                                    camera = nameplateCamera,
                                    title = when {
                                        following && nameplateCamera == null -> lookName
                                        following -> nameplateCamera!!.model
                                        else -> pinnedCamera?.model ?: lookName
                                    },
                                    subtitle = when {
                                        following && nameplateCamera == null -> "通用影调"
                                        following -> lookName
                                        else -> pinnedCamera?.brand.orEmpty()
                                    },
                                    onPick = {
                                        if (following) adjustmentTab = AdjustmentTab.LOOK
                                        else showMascotPicker = true
                                    },
                                    modifier = Modifier.fillMaxWidth().height(nameplateHeight))
                            }
                            Viewfinder(model, state, settings, retry, false, compositionSource, compositionOverlay, compositionState,
                                Modifier.width(framedWidth).aspectRatio(3f / 4f), framed = true, onRetry = { retry++ })
                            ViewfinderOverlayBar(state, settings, inFlight, loaded, model,
                                Modifier.fillMaxWidth().weight(1f), framed = true,
                                onTab = { adjustmentTab = it },
                                latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                        }
                        if (adjustmentTab != null) {
                            ControlBottomSheet(adjustmentTab!!, onDismiss = { adjustmentTab = null },
                                onTab = { adjustmentTab = it },
                                state = state, settings = settings, importing = lutLoading, model = model,
                                importLut = { lutPicker.launch(arrayOf("*/*")) },
                                openMascotPicker = { showMascotPicker = true },
                                compositionState = compositionState)
                        }
                    } else {
                        // Small landscape / split-screen: the viewfinder owns the space and
                        // shutter/focal controls float over its lower edge.
                        Viewfinder(model, state, settings, retry, landscape, compositionSource, compositionOverlay, compositionState,
                            Modifier.fillMaxSize(), onRetry = { retry++ })
                        ViewfinderOverlayBar(state, settings, inFlight, loaded, model,
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            onTab = { adjustmentTab = it },
                            latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                        if (adjustmentTab != null) {
                            ControlBottomSheet(adjustmentTab!!, onDismiss = { adjustmentTab = null },
                                onTab = { adjustmentTab = it },
                                state = state, settings = settings, importing = lutLoading, model = model,
                                importLut = { lutPicker.launch(arrayOf("*/*")) },
                                openMascotPicker = { showMascotPicker = true },
                                compositionState = compositionState)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
    if (showCapabilities) CapabilitiesDialog(state.capabilities, state.error, { showCapabilities = false })
    if (showMascotPicker) MascotPicker(settings.mascotCamera, settings.captureOrientation,
        model::setMascotCamera, model::setCaptureOrientation,
        onDiagnostics = { showCapabilities = true }, onDismiss = { showMascotPicker = false })
}

@Composable
private fun PermissionPanel(denied: Boolean, request: () -> Unit, settings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("让相机准备好", style = MaterialTheme.typography.headlineSmall)
        Text("途帧仅需相机权限。照片写入系统相册，不读取你的图库。",
            Modifier.padding(vertical = 16.dp).widthIn(max = 360.dp), textAlign = TextAlign.Center)
        Button(onClick = request) { Text(if (denied) "重新授权" else "允许使用相机") }
        if (denied) TextButton(onClick = settings) { Text("前往系统设置") }
    }
}

@Composable
@SuppressLint("MissingPermission") // CameraScreen only composes this after checking CAMERA permission.
private fun Viewfinder(
    model: CameraViewModel,
    state: CameraState,
    settings: AppSettings,
    retry: Int,
    landscape: Boolean,
    compositionSource: dev.streetsnap.camera.CompositionFrameSource?,
    compositionOverlay: CompositionOverlay?,
    compositionState: CompositionUiState,
    modifier: Modifier,
    onRetry: () -> Unit,
    // Portrait camera-back layout: the caller already sizes this composable to the exact
    // 3:4 screen, so overlays only need a small lift off its bottom edge.
    framed: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val preview = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(preview, lifecycle) {
        val observer = Observer<PreviewView.StreamState> { model.onPreviewStreaming(it == PreviewView.StreamState.STREAMING) }
        preview.previewStreamState.observe(lifecycle, observer)
        onDispose {
            preview.previewStreamState.removeObserver(observer)
            model.onPreviewStreaming(false)
        }
    }
    DisposableEffect(preview, model) {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        fun updateRotation() {
            preview.display?.let { model.camera.updateTargetRotation(it.rotation) }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updateRotation()
            override fun onDisplayRemoved(displayId: Int) = updateRotation()
            override fun onDisplayChanged(displayId: Int) {
                if (preview.display?.displayId == displayId) updateRotation()
            }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var bindingError by remember { mutableStateOf<String?>(null) }
    val previewError by model.previewError.collectAsStateWithLifecycle()
    val controlsRestored by model.controlsRestored.collectAsStateWithLifecycle()
    val streaming by model.previewStreaming.collectAsStateWithLifecycle()
    val histogram by model.histogram.collectAsStateWithLifecycle()
    val osdMode = settings.viewfinderOsd
    val meteringWanted = osdMode != ViewfinderOsd.OFF
    val hasSize = width > 0 && height > 0
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val manualFocus = state.settings.focusDistanceDiopters != null
    val canTapFocus = state.isReady && !manualFocus && state.capabilities?.supportsFocusMetering == true
    LaunchedEffect(manualFocus, retry, landscape, width, height) { focusPoint = null }
    LaunchedEffect(focusPoint, state.isFocusing, state.isFocusLocked) {
        if (!state.isFocusing && !state.isFocusLocked) {
            delay(4_000)
            focusPoint = null
        }
    }
    // The adjustment panel only changes the preview's size, not its 4:3 framing.
    // PreviewView handles those resizes without tearing down the camera session.
    LaunchedEffect(preview, lifecycle, retry, hasSize, landscape, compositionSource, meteringWanted) {
        if (!hasSize) return@LaunchedEffect
        model.onBindingStarted()
        bindingError = null
        model.clearPreviewError()
        try {
            suspendCancellableCoroutine { continuation ->
                preview.doOnLayout { if (continuation.isActive) continuation.resume(Unit) }
            }
            val rotation = preview.display?.rotation ?: Surface.ROTATION_0
            val ratio = if (landscape) android.util.Rational(4, 3) else android.util.Rational(3, 4)
            val viewPort = ViewPort.Builder(ratio, rotation)
                .setScaleType(ViewPort.FILL_CENTER).build()
            model.camera.bind(lifecycle, preview.surfaceProvider, model.colors.previewEffect(model::onPreviewError), viewPort, rotation,
                compositionFrames = compositionSource,
                lumaMeter = if (meteringWanted) model.lumaMeter else null)
            model.onCameraBound()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            bindingError = error.message ?: "相机未能启动"
        }
    }
    BoxWithConstraints(modifier.background(Color.Black)) {
        val ratio = if (landscape) 4f / 3f else 3f / 4f
        val previewWidth = min(maxWidth.value, maxHeight.value * ratio).dp
        val previewHeight = previewWidth / ratio
        // Framed portrait: the box already is the exact 3:4 screen between nameplate and
        // control cluster, so cards hug the screen edge. Floating portrait lifts cards
        // above the shutter/focal bar; landscape barely lifts them at all.
        val floatingClearance = when {
            framed -> 16.dp
            maxHeight > maxWidth -> 92.dp
            else -> 10.dp
        }
        // Framed fills its box. The floating portrait path pins to TopCenter; landscape
        // keeps the 3:4 picture centred beside/below its chrome.
        val previewAlignment = if (framed || maxWidth > maxHeight) Alignment.Center else Alignment.TopCenter
        Box(Modifier.size(previewWidth, previewHeight).align(previewAlignment)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().onSizeChanged {
                width = it.width
                height = it.height
            })
            val roll = rememberLevelAngle(settings.level, preview)
            // Rule advice refreshes several times per second; ease the rectangle so the
            // guide tracks the scene without visibly jumping between nearby crops.
            val smoothCrop = rememberSmoothCrop(compositionOverlay)
            Canvas(Modifier.fillMaxSize().pointerInput(preview, canTapFocus) {
                detectTapGestures(
                    onTap = { position ->
                        if (canTapFocus) {
                            focusPoint = Offset(position.x / width, position.y / height)
                            model.focus(preview.meteringPointFactory.createPoint(position.x, position.y))
                        }
                    },
                    onLongPress = { position ->
                        if (canTapFocus) {
                            focusPoint = Offset(position.x / width, position.y / height)
                            model.focus(preview.meteringPointFactory.createPoint(position.x, position.y), lock = true)
                        }
                    },
                )
            }) {
                if (settings.grid && compositionOverlay == null) {
                    val color = Color.White.copy(alpha = .32f)
                    for (i in 1..2) {
                        drawLine(color, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                        drawLine(color, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                    }
                }
                if (settings.level && roll != null) {
                    // Horizon level near the top edge (DSLR convention), never over the
                    // centre of the picture: a faint fixed reference plus a rolling line;
                    // the two coincide and turn lime when the phone is level.
                    val pivot = Offset(size.width / 2f, size.height * .085f)
                    val halfLength = min(size.width * .075f, 28.dp.toPx())
                    val tick = 4.dp.toPx()
                    val reference = Color.White.copy(alpha = .32f)
                    drawLine(reference, Offset(pivot.x - halfLength, pivot.y), Offset(pivot.x + halfLength, pivot.y), 1.5.dp.toPx())
                    drawLine(reference, Offset(pivot.x - halfLength, pivot.y - tick), Offset(pivot.x - halfLength, pivot.y + tick), 1.dp.toPx())
                    drawLine(reference, Offset(pivot.x + halfLength, pivot.y - tick), Offset(pivot.x + halfLength, pivot.y + tick), 1.dp.toPx())
                    val radians = Math.toRadians(roll.toDouble())
                    val delta = Offset((kotlin.math.cos(radians) * halfLength).toFloat(), (kotlin.math.sin(radians) * halfLength).toFloat())
                    val levelColor = if (abs(roll) < 1.5f) Color(0xFFE2FF79) else Color.White.copy(alpha = .78f)
                    drawLine(levelColor, pivot - delta, pivot + delta, 2.dp.toPx())
                }
                if (meteringWanted) histogram?.let { counts ->
                    val panelWidth = 118.dp.toPx()
                    val panelHeight = 48.dp.toPx()
                    val margin = 10.dp.toPx()
                    // Below the top-right composition/realtime/AF pills.
                    val panelLeft = size.width - panelWidth - margin
                    val panelTop = 52.dp.toPx()
                    val corner = 6.dp.toPx()
                    drawRoundRect(Color.Black.copy(alpha = .42f),
                        topLeft = Offset(panelLeft, panelTop),
                        size = androidx.compose.ui.geometry.Size(panelWidth, panelHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner))
                    val tallest = counts.maxOrNull()?.toFloat() ?: 0f
                    if (tallest > 0f) {
                        val innerPad = 3.dp.toPx()
                        val plotHeight = panelHeight - innerPad * 2
                        val barWidth = panelWidth / counts.size
                        counts.forEachIndexed { index, count ->
                            val barHeight = (count / tallest * plotHeight).coerceAtLeast(1f)
                            drawRect(Color.White.copy(alpha = .72f),
                                topLeft = Offset(panelLeft + index * barWidth, panelTop + panelHeight - innerPad - barHeight),
                                size = androidx.compose.ui.geometry.Size(barWidth * .82f, barHeight))
                        }
                    }
                }
                focusPoint?.let { point ->
                    val radius = minOf(22.dp.toPx(), size.minDimension / 4f)
                    val x = (point.x * size.width).coerceIn(radius, size.width - radius)
                    val y = (point.y * size.height).coerceIn(radius, size.height - radius)
                    val color = when {
                        state.isFocusing -> Color.White
                        state.focusResult == true -> Color(0xFFE2FF79)
                        state.focusResult == false -> Color(0xFFFFB36B)
                        else -> Color.White
                    }
                    drawRect(color, Offset(x - radius, y - radius),
                        androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(1.5.dp.toPx()))
                }
                compositionOverlay?.let { overlay ->
                    // Reading the animated values here (inside the draw scope) means
                    // animation ticks only redraw this canvas, not recompose the screen.
                    val crop = smoothCrop.crop
                    if (crop != null) {
                    val left = (crop.left.coerceIn(0f, 1f) * size.width)
                    val top = (crop.top.coerceIn(0f, 1f) * size.height)
                    val right = (crop.right.coerceIn(0f, 1f) * size.width)
                    val bottom = (crop.bottom.coerceIn(0f, 1f) * size.height)
                    if (right > left && bottom > top) {
                        val tint = if (overlay.origin == AdviceOrigin.MODEL_SELECTION) Color(0xFFE2FF79) else Color(0xFFB7E3F6)
                        val shade = Color.Black.copy(alpha = .26f)
                        drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, top))
                        drawRect(shade, Offset(0f, bottom), androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                        drawRect(shade, Offset(0f, top), androidx.compose.ui.geometry.Size(left, bottom - top))
                        drawRect(shade, Offset(right, top), androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
                        drawRect(tint.copy(alpha = .95f), Offset(left, top),
                            androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
                        for (i in 1..2) {
                            val x = left + (right - left) * i / 3f
                            val y = top + (bottom - top) * i / 3f
                            drawLine(tint.copy(alpha = .40f), Offset(x, top), Offset(x, bottom), .75.dp.toPx())
                            drawLine(tint.copy(alpha = .40f), Offset(left, y), Offset(right, y), .75.dp.toPx())
                        }
                    }
                    }
                }
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Single quiet corner entry: off -> histogram -> histogram + exposure readout -> off.
                OsdCycleButton(osdMode, model::cycleViewfinderOsd)
                Spacer(Modifier.weight(1f))
                OverlayPill(
                    label = if (!compositionState.isRequestRunning) "构图" else "分析中",
                    enabled = state.isReady && !compositionState.isRequestRunning,
                    description = "分析一次构图",
                    onClick = model::requestComposition)
                OverlayPill(
                    label = if (settings.compositionRealtime) "实时 · 开" else "实时",
                    enabled = true, description = "实时构图开关", selected = settings.compositionRealtime,
                    onClick = { model.setCompositionRealtime(!settings.compositionRealtime) })
                val focusLabel = when {
                    manualFocus -> "MF"
                    state.isFocusLocked -> "AF-L"
                    state.capabilities?.autoFocusMode != null -> "AF"
                    else -> "固定焦点"
                }
                OverlayPill(focusLabel, onClick = {
                        focusPoint = null
                        if (state.isFocusLocked) model.autoFocus() else model.toggleFocusMode()
                    },
                    enabled = state.isReady && (if (manualFocus) state.capabilities?.autoFocusMode != null
                        else state.isFocusLocked || state.capabilities?.supportsManualFocus == true),
                    selected = manualFocus || state.isFocusLocked,
                    description = when {
                        state.isFocusLocked -> "解除对焦锁定"
                        manualFocus -> "切换自动对焦"
                        state.capabilities?.supportsManualFocus == true -> "切换手动对焦"
                        else -> "当前对焦模式"
                    })
            }
            // DSLR-style readout under the histogram. Every value is reported by the
            // camera: automatic slots read AUTO rather than a guessed number, aperture is
            // shown only when the device actually reports a fixed one (most phones do not).
            if (osdMode == ViewfinderOsd.PARAMS) {
                val caps = state.capabilities
                val capture = state.settings
                val ev = capture.exposureCompensationIndex * (caps?.exposureCompensationStepEv ?: 0.0)
                Column(Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 106.dp).width(118.dp)
                    .background(Color.Black.copy(alpha = .42f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)) {
                    OsdReadoutRow(capture.exposureMode.label,
                        if (capture.exposureMode == ExposureMode.MANUAL) "EV —" else "EV %+.1f".format(ev))
                    OsdReadoutRow("快门", capture.exposureTimeNs?.let(::shutterLabel) ?: "AUTO")
                    OsdReadoutRow("ISO", capture.iso?.toString() ?: "AUTO")
                    caps?.fixedAperture?.let { aperture ->
                        OsdReadoutRow("光圈", "f/%.1f".format(aperture))
                    }
                }
            }
            // Advice card sits above the floating shutter/focal bar (clearance computed above).
            if (settings.compositionEnabled) {
                Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = floatingClearance)
                    .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                    compositionOverlay?.suggestion?.modelText?.let {
                        Text(it, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    compositionOverlay?.easterEgg?.let {
                        Text(it, color = Color(0xFFE2FF79), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(compositionTimingLabel(compositionState), color = Color.White.copy(alpha = .78f), fontSize = 10.sp, maxLines = 2)
                }
            }
            val focusHint = when {
                state.isFocusing -> "对焦中"
                state.isFocusLocked -> "对焦已锁定 · 点 AF-L 解除"
                focusPoint != null && state.focusResult == false -> "未合焦 · 可重试或直接拍摄"
                else -> null
            }
            focusHint?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(bottom = floatingClearance + 8.dp),
                color = Color.White, fontSize = 12.sp) }
        }
        val error = bindingError ?: previewError ?: state.error
        if (error != null) {
            Column(Modifier.background(Color.Black.copy(alpha = .84f)).padding(24.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("相机暂不可用", style = MaterialTheme.typography.titleMedium)
                Text(error, Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text("重试") }
            }
        } else if (!state.isBound || !controlsRestored || !streaming) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        }
    }
}

private enum class AdjustmentTab(val label: String) {
    LOOK("影调"), FILM("胶片"), WHITE_BALANCE("白平衡"), SHOOTING("拍摄"),
}

private enum class LookParameter(val label: String, val signed: Boolean = true) {
    INTENSITY("强度", false), SATURATION("饱和度"), HUE("色相"), CONTRAST("对比度"),
    BRIGHTNESS("亮度"), HIGHLIGHTS("高光"), SHADOWS("阴影"), SHARPNESS("锐度"),
    CLARITY("清晰度"), GRAIN("颗粒", false), VIGNETTE("暗角", false), DIFFUSION("柔光", false),
}

/** A currently-active manual override surfaced as a tappable/long-press-resettable chip. */
private data class ActiveOverride(
    val label: String,
    val tab: AdjustmentTab,
    val resetDescription: String,
    val onReset: () -> Unit,
)

/** Small translucent chip that floats over the viewfinder instead of sitting on a grey bar. */
@Composable
private fun OverlayPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    description: String? = null,
    onLongClick: (() -> Unit)? = null,
    longClickDescription: String? = null,
) {
    val highlighted = selected
    val container = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = .20f)
        else Color.Black.copy(alpha = .42f)
    val content = if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .92f)
    Box(modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(container)
        .combinedClickable(
            enabled = enabled, role = Role.Button,
            onLongClick = onLongClick, onLongClickLabel = longClickDescription,
            onClick = onClick,
        )
        .semantics {
            if (description != null) contentDescription = description
            this.selected = selected
        }.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) content else content.copy(alpha = .4f),
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top-left corner entry for the viewfinder OSD cycle; quiet when off, primary-tinted while showing. */
@Composable
private fun OsdCycleButton(mode: String, onCycle: () -> Unit) {
    val active = mode != ViewfinderOsd.OFF
    val description = when (mode) {
        ViewfinderOsd.HISTOGRAM -> "正在显示直方图，点按追加拍摄参数"
        ViewfinderOsd.PARAMS -> "正在显示直方图与拍摄参数，点按关闭取景信息"
        else -> "显示曝光直方图"
    }
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .78f)
    Box(Modifier.size(38.dp).clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .20f) else Color.Black.copy(alpha = .32f))
        .clickable(role = Role.Button, onClick = onCycle)
        .semantics { contentDescription = description; this.selected = active },
        contentAlignment = Alignment.Center) {
        // Tiny histogram glyph: three bars of different height.
        Canvas(Modifier.size(16.dp)) {
            val fractions = listOf(.45f, .95f, .65f)
            val gap = 1.5.dp.toPx()
            val barWidth = (size.width - gap * (fractions.size - 1)) / fractions.size
            fractions.forEachIndexed { index, fraction ->
                val barHeight = size.height * fraction
                drawRect(tint,
                    topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight))
            }
        }
    }
}

/** One labelled, reported-by-camera value in the OSD readout; never a fabricated number. */
@Composable
private fun OsdReadoutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = .55f), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(value, color = Color.White.copy(alpha = .92f), fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/**
 * Phone portrait top chrome. In follow-look mode the body tracks the active look/film
 * family (the general family shows the neutral lens mark) and the subtitle names the
 * look; a pinned body shows its brand. Tap: follow-look opens the look sheet, a pinned
 * body opens the picker. Sits above the 3:4 screen like the plate on a camera back.
 */
@Composable
private fun ViewfinderNameplate(
    camera: CameraModel?,
    title: String,
    subtitle: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Same mid-grey card as the body-picker tiles (#2D3335): the dark vector bodies
        // only separate from a lighter field, not from black chrome.
        Box(Modifier.size(92.dp, 52.dp).clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF2D3335))
            .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(11.dp))
            .clickable(role = Role.Button, onClick = onPick)
            .semantics {
                contentDescription = if (camera != null) "相机铭牌：${camera.brand} ${camera.model}，点按更换"
                    else "相机铭牌：$title，点按更换影调"
            },
            contentAlignment = Alignment.Center) {
            // Canvas has no intrinsic size; an explicit size is mandatory in the wrapped row.
            if (camera != null) CameraArtwork(camera, Modifier.size(82.dp, 50.dp))
            else NeutralLookArtwork(Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White.copy(alpha = .92f), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = .5f), fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Phone portrait capture surface. Framed mode is an opaque camera-back control cluster
 * beneath the 3:4 screen (a hairline marks the edge, then chips/focal/shutter sit as one
 * vertically centred group); the small-landscape fallback keeps controls floating over
 * the viewfinder on a gradient.
 * Only the high-frequency focal choice and the shutter stay; looks, white balance and
 * other pro controls are surfaced here *only* while a manual override is active (a
 * tappable chip that jumps straight to the right sheet section), otherwise they live
 * in [ControlBottomSheet].
 */
@Composable
private fun ViewfinderOverlayBar(
    state: CameraState,
    settings: AppSettings,
    inFlight: Int,
    loaded: Boolean,
    model: CameraViewModel,
    modifier: Modifier = Modifier,
    onTab: (AdjustmentTab?) -> Unit,
    latestPhoto: Uri?,
    openPhoto: (Uri) -> Unit,
    framed: Boolean = false,
) {
    val focalLengths = listOf(FocalLength.MM28, FocalLength.MM35, FocalLength.MM50)
    val supportedFocalLengths = state.capabilities?.focalLengthOptions.orEmpty()
    val exposureMode = state.settings.exposureMode
    val ev = state.settings.exposureCompensationIndex * (state.capabilities?.exposureCompensationStepEv ?: 0.0)
    val wbName = state.settings.whiteBalance.takeIf { it != WhiteBalanceMode.AUTO }?.displayName

    // Only *manual overrides that can ruin a shot* get a persistent chip (Halide Mark III
    // pattern): non-auto WB, diffusion, MF, S/M exposure, EV. Tap jumps to the section,
    // long-press resets just that override. The creative look/film choice is WYSIWYG, so
    // it stays inside the sheet instead of taking a permanent slot.
    val overrides: List<ActiveOverride> = buildList {
        wbName?.let {
            add(ActiveOverride("白平衡 · $it", AdjustmentTab.WHITE_BALANCE, "复位白平衡") {
                model.setWhiteBalance(WhiteBalanceMode.AUTO)
                model.resetWhiteBalanceShift()
            })
        }
        if (settings.hdfEnabled) add(ActiveOverride("柔光", AdjustmentTab.LOOK, "关闭柔光") { model.toggleHdf() })
        if (settings.hdrEnabled) add(ActiveOverride("HDR", AdjustmentTab.LOOK, "关闭 HDR") { model.toggleHdr() })
        if (state.settings.focusDistanceDiopters != null)
            add(ActiveOverride("MF", AdjustmentTab.SHOOTING, "恢复自动对焦") { model.autoFocus() })
        if (exposureMode != ExposureMode.PROGRAM) add(ActiveOverride(
            when (exposureMode) { ExposureMode.MANUAL -> "M · ISO ${state.settings.iso ?: "—"}"; else -> "S" },
            AdjustmentTab.SHOOTING, "复位拍摄参数", model::resetShootingParameters))
        if (kotlin.math.abs(ev) > 0.001f && exposureMode != ExposureMode.MANUAL)
            add(ActiveOverride("EV %+.1f".format(ev), AdjustmentTab.SHOOTING, "曝光归零") { model.setExposure(0) })
    }

    val chrome = if (framed) Modifier.background(Color.Black)
        else Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .58f))))
    Column(modifier.then(chrome)
        .padding(top = if (framed) 8.dp else 46.dp, bottom = if (framed) 8.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // Hairline that separates the 3:4 screen from the camera-back control cluster.
        if (framed) HorizontalDivider(thickness = .8.dp, color = Color.White.copy(alpha = .07f))
        // Framed mode: the control group (chips, focal, shutter) is vertically centred as
        // one block, so on tall 20:9 screens the slack splits above/below it instead of
        // opening a void between the focal row and the shutter (T22).
        if (framed) Spacer(Modifier.weight(1f))
        // Active manual overrides: visible tappable reminders that jump to the section.
        if (overrides.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (framed) 3.dp else 4.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                overrides.forEach { override ->
                    OverlayPill(override.label, selected = true,
                        onClick = { onTab(override.tab) }, description = "打开${override.label}调整",
                        onLongClick = override.onReset, longClickDescription = override.resetDescription)
                }
            }
        }
        // Focal lengths: the one pro control worth keeping on the capture screen.
        // In framed mode it belongs to the shutter group with a fixed, deliberate gap.
        Row(Modifier.padding(bottom = if (framed) 14.dp else 6.dp).clip(RoundedCornerShape(22.dp))
            .background(Color.Black.copy(alpha = .42f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            focalLengths.forEach { focal ->
                val enabled = focal in supportedFocalLengths && state.isReady && loaded
                val selected = state.settings.focalLength == focal
                Box(Modifier.height(38.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.Button) { model.setFocalLength(focal) }
                    .semantics { this.selected = selected; contentDescription = "焦段 ${focal.label}" }
                    .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(focal.label, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        color = when {
                            !enabled -> Color.White.copy(alpha = .32f)
                            selected -> MaterialTheme.colorScheme.onPrimary
                            else -> Color.White.copy(alpha = .92f)
                        })
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(92.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp), contentAlignment = Alignment.Center) {
                RecentPhoto(latestPhoto, openPhoto)
            }
            val canShoot = state.isReady && !state.isCapturing && inFlight < 2 && loaded
            Box(Modifier.size(78.dp).border(2.dp, if (canShoot) Color.White else Color.Gray, CircleShape)
                .padding(7.dp).clip(CircleShape).background(if (canShoot) MaterialTheme.colorScheme.primary else Color.DarkGray)
                .clickable(enabled = canShoot, role = Role.Button) { model.shoot() }
                .semantics { contentDescription = "拍照" }, contentAlignment = Alignment.Center) {
                if (state.isCapturing) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else if (inFlight > 0) Text("$inFlight / 2", fontFamily = FontFamily.Monospace,
                    color = if (canShoot) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp)
            }
            // Single gateway to every pro control (look/film/WB/shooting/composition).
            // The sheet's own scrim/drag handle dismisses it, so this is always "open".
            Column(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp).clip(RoundedCornerShape(14.dp))
                .clickable(enabled = loaded, role = Role.Button) { onTab(AdjustmentTab.LOOK) }
                .semantics { contentDescription = "打开控制面板" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("调整", fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                Text(when (exposureMode) {
                        ExposureMode.MANUAL -> "M · ISO ${state.settings.iso ?: "—"}"
                        ExposureMode.SHUTTER_PRIORITY -> "S · ${shutterLabel(state.settings.exposureTimeNs ?: 8_000_000L)}"
                        ExposureMode.PROGRAM -> "P · %+.1f EV".format(ev)
                    },
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = Color.White.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Counterweight to the leading spacer: keeps the whole control group centred.
        if (framed) Spacer(Modifier.weight(1f))
    }
}

/** One shallow, modal bottom sheet for every pro control; opens already on the chosen section. */
@Composable
private fun ControlBottomSheet(
    tab: AdjustmentTab,
    onDismiss: () -> Unit,
    onTab: (AdjustmentTab?) -> Unit,
    state: CameraState,
    settings: AppSettings,
    importing: Boolean,
    model: CameraViewModel,
    importLut: () -> Unit,
    openMascotPicker: () -> Unit,
    compositionState: CompositionUiState,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color.Transparent, scrimColor = Color.Black.copy(alpha = .55f),
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) }) {
        AdjustmentPanel(state, settings, importing, model, tab, onTab = onTab, importLut,
            openMascotPicker, compositionState,
            Modifier.fillMaxWidth().height(500.dp).padding(horizontal = 10.dp).padding(bottom = 10.dp))
    }
}

@Composable
private fun ShootingControls(
    state: CameraState,
    settings: AppSettings,
    inFlight: Int,
    loaded: Boolean,
    importing: Boolean,
    model: CameraViewModel,
    modifier: Modifier,
    adjustmentTab: AdjustmentTab?,
    onTab: (AdjustmentTab?) -> Unit,
    sidePanel: Boolean,
    panelHeight: Dp,
    importLut: () -> Unit,
    openMascotPicker: () -> Unit,
    compositionState: CompositionUiState,
    latestPhoto: Uri?,
    openPhoto: (Uri) -> Unit,
) {
    val focalLengths = listOf(FocalLength.MM28, FocalLength.MM35, FocalLength.MM50)
    val capabilities = state.capabilities
    val supportedFocalLengths = capabilities?.focalLengthOptions.orEmpty()
    val minimumFocalWidth = maxOf(48.dp, (48f * LocalDensity.current.fontScale).dp)
    val focalHint = when {
        capabilities == null -> "焦段信息尚不可用"
        focalLengths.none { it in supportedFocalLengths } && state.settings.focalLength == FocalLength.NATIVE ->
            if (capabilities.nativeEquivalentFocalLengthMm == null) "焦段信息不可用 · 当前为原生视角"
            else "三档视角暂不可用 · 当前为原生视角"
        focalLengths.any { it !in supportedFocalLengths } -> "灰色视角暂不可用"
        else -> null
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val distribute = maxWidth >= minimumFocalWidth * 3 + 8.dp
                Row(if (distribute) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    focalLengths.forEach { focal ->
                        val enabled = focal in supportedFocalLengths && state.isReady && loaded
                        val selected = enabled && state.settings.focalLength == focal
                        TextButton(onClick = { model.setFocalLength(focal) }, enabled = enabled,
                            modifier = (if (distribute) Modifier.weight(1f) else Modifier.widthIn(min = minimumFocalWidth))
                                .height(48.dp).semantics { this.selected = selected },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)) {
                            Text(focal.label, Modifier.background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
                                RoundedCornerShape(7.dp)).padding(horizontal = 4.dp, vertical = 4.dp),
                                color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                                else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            TextButton(onClick = {
                onTab(if (adjustmentTab == AdjustmentTab.WHITE_BALANCE) null else AdjustmentTab.WHITE_BALANCE)
            }, enabled = loaded, modifier = Modifier.height(48.dp).widthIn(min = 56.dp)
                .semantics { contentDescription = "白平衡与漂移" }) {
                Text("WB", fontFamily = FontFamily.Monospace,
                    color = if (adjustmentTab == AdjustmentTab.WHITE_BALANCE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.width(56.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (settings.hdfEnabled) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .toggleable(value = settings.hdfEnabled, enabled = loaded, role = Role.Switch) { model.toggleHdf() }
                .semantics {
                    contentDescription = "柔光开关"
                    stateDescription = if (settings.hdfEnabled) "开启" else "关闭"
                }, contentAlignment = Alignment.Center) {
                Text("HDF", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = if (settings.hdfEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.width(56.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (settings.hdrEnabled) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .toggleable(value = settings.hdrEnabled, enabled = loaded, role = Role.Switch) { model.toggleHdr() }
                .semantics {
                    contentDescription = "HDR 效果开关"
                    stateDescription = if (settings.hdrEnabled) "开启" else "关闭"
                }, contentAlignment = Alignment.Center) {
                Text("HDR", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = if (settings.hdrEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (focalHint != null) Text(focalHint, Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (adjustmentTab != AdjustmentTab.LOOK) {
            Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                ControlButton(FilmRegistry.find(settings.filmId)?.displayName ?: "影调", settings.filmId != null,
                    enabled = loaded, onClick = { onTab(AdjustmentTab.FILM) })
                // Keep the collapsed surface camera-like: a few high-frequency choices
                // are visible, while the complete recipe catalog remains in the panel.
                val quickPresets = buildList {
                    add(ColorPreset.ORIGINAL)
                    add(ColorPreset.POSITIVE)
                    add(ColorPreset.NEGATIVE)
                    ColorPreset.entries.firstOrNull { it.name == settings.preset }?.let { selected ->
                        if (selected !in this) add(selected)
                    }
                }
                quickPresets.forEach { preset ->
                    ControlButton(preset.displayName, settings.filmId == null && settings.preset == preset.name,
                        enabled = loaded, onClick = { model.setPreset(preset) })
                }
            }
        }
        if (adjustmentTab != null) {
            AdjustmentPanel(state, settings, importing, model, adjustmentTab, onTab, importLut, openMascotPicker, compositionState,
                if (sidePanel) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()
                    .height(panelHeight + if (adjustmentTab == AdjustmentTab.LOOK) 48.dp else 0.dp))
        } else if (sidePanel) Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(90.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp), contentAlignment = Alignment.Center) {
                RecentPhoto(latestPhoto, openPhoto)
            }
            val canShoot = state.isReady && !state.isCapturing && inFlight < 2 && loaded
            Box(Modifier.size(78.dp).border(2.dp, if (canShoot) Color.White else Color.Gray, CircleShape)
                .padding(7.dp).clip(CircleShape).background(if (canShoot) MaterialTheme.colorScheme.primary else Color.DarkGray)
                .clickable(enabled = canShoot, role = Role.Button) { model.shoot() }
                .semantics { contentDescription = "拍照" }, contentAlignment = Alignment.Center) {
                if (state.isCapturing) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else if (inFlight > 0) Text("$inFlight / 2", fontFamily = FontFamily.Monospace,
                    color = if (canShoot) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp)
            }
            Column(Modifier.weight(1f, fill = false).width(80.dp).height(72.dp).clip(RoundedCornerShape(14.dp))
                .clickable(enabled = loaded, role = Role.Button) {
                    onTab(if (adjustmentTab == null) AdjustmentTab.LOOK else null)
                }.semantics { contentDescription = if (adjustmentTab == null) "打开调整面板" else "收起调整面板" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (adjustmentTab == null) "ADJ" else "收起", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (adjustmentTab != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                val ev = state.settings.exposureCompensationIndex * (state.capabilities?.exposureCompensationStepEv ?: 0.0)
                Text(when (state.settings.exposureMode) {
                    ExposureMode.MANUAL -> "M · ISO ${state.settings.iso}"
                    ExposureMode.SHUTTER_PRIORITY -> "S · ${shutterLabel(state.settings.exposureTimeNs ?: 8_000_000L)}"
                    ExposureMode.PROGRAM -> "P · %+.1f EV".format(ev)
                },
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RecentPhoto(uri: Uri?, openPhoto: (Uri) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val cancellation = remember(uri, resolver) { CancellationSignal() }
    DisposableEffect(cancellation) { onDispose { cancellation.cancel() } }
    val thumbnail by produceState<ImageBitmap?>(null, uri, resolver) {
        value = null
        if (uri != null) {
            try {
                value = withContext(Dispatchers.IO) {
                    resolver.loadThumbnail(uri, Size(160, 160), cancellation).asImageBitmap()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The saved URI remains tappable even if its thumbnail is unavailable.
            }
        }
    }
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(9.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, outline.copy(alpha = .25f), RoundedCornerShape(9.dp))
        .clickable(enabled = uri != null, role = Role.Button) { uri?.let(openPhoto) }
        .semantics { contentDescription = if (uri != null) "查看最近拍摄的照片" else "尚无照片" },
        contentAlignment = Alignment.Center) {
        thumbnail?.let { bitmap ->
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Canvas(Modifier.size(24.dp)) {
            drawRect(outline, style = Stroke(1.5.dp.toPx()))
            drawCircle(outline, 2.dp.toPx(), Offset(size.width * .7f, size.height * .3f))
            drawLine(outline, Offset(0f, size.height * .8f), Offset(size.width * .35f, size.height * .45f), 1.5.dp.toPx())
            drawLine(outline, Offset(size.width * .35f, size.height * .45f), Offset(size.width * .8f, size.height), 1.5.dp.toPx())
        }
    }
}

@Composable
private fun ControlButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.height(48.dp).widthIn(min = 48.dp)
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(10.dp)) {
        Text(label, Modifier.background(
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
            RoundedCornerShape(7.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
            color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdjustmentPanel(
    state: CameraState,
    settings: AppSettings,
    importing: Boolean,
    model: CameraViewModel,
    tab: AdjustmentTab,
    onTab: (AdjustmentTab?) -> Unit,
    importLut: () -> Unit,
    openMascotPicker: () -> Unit,
    compositionState: CompositionUiState,
    modifier: Modifier,
) {
    val contentScroll = rememberScrollState()
    LaunchedEffect(tab) { contentScroll.scrollTo(0) }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            AdjustmentTab.entries.forEach { item ->
                ControlButton(item.label, item == tab, onClick = { onTab(item) })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(horizontal = 12.dp)) {
            when (tab) {
                AdjustmentTab.LOOK -> LookAdjustments(settings, importing, model, importLut)
                AdjustmentTab.FILM -> FilmAdjustments(settings, model)
                AdjustmentTab.WHITE_BALANCE -> WhiteBalanceAdjustments(state, settings, model)
                AdjustmentTab.SHOOTING -> ShootingAdjustments(state, settings, model, openMascotPicker, compositionState)
            }
        }
    }
}

@Composable
private fun LookAdjustments(settings: AppSettings, importing: Boolean, model: CameraViewModel, importLut: () -> Unit) {
    CameraLookPicker(settings, model::setPreset)
    var parameter by remember { mutableStateOf(LookParameter.SATURATION) }
    val look = settings.activeLook
    val value = when (parameter) {
        LookParameter.INTENSITY -> look.intensity
        LookParameter.SATURATION -> look.saturation
        LookParameter.HUE -> look.hue
        LookParameter.CONTRAST -> look.contrast
        LookParameter.BRIGHTNESS -> look.brightness
        LookParameter.HIGHLIGHTS -> look.highlights
        LookParameter.SHADOWS -> look.shadows
        LookParameter.SHARPNESS -> look.sharpness
        LookParameter.CLARITY -> look.clarity
        LookParameter.GRAIN -> look.grain
        LookParameter.VIGNETTE -> look.vignette
        LookParameter.DIFFUSION -> settings.hdfStrength
    }
    val setValue: (Float) -> Unit = { amount ->
        when (parameter) {
            LookParameter.INTENSITY -> model.setColor(intensity = amount)
            LookParameter.SATURATION -> model.setColor(saturation = amount)
            LookParameter.HUE -> model.setColor(hue = amount)
            LookParameter.CONTRAST -> model.setColor(contrast = amount)
            LookParameter.BRIGHTNESS -> model.setColor(brightness = amount)
            LookParameter.HIGHLIGHTS -> model.setColor(highlights = amount)
            LookParameter.SHADOWS -> model.setColor(shadows = amount)
            LookParameter.SHARPNESS -> model.setColor(sharpness = amount)
            LookParameter.CLARITY -> model.setColor(clarity = amount)
            LookParameter.GRAIN -> model.setColor(grain = amount)
            LookParameter.VIGNETTE -> model.setColor(vignette = amount)
            LookParameter.DIFFUSION -> model.setHdfStrength(amount)
        }
    }
    Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        LookParameter.entries.forEach { item ->
            ControlButton(item.label, item == parameter, onClick = { parameter = item })
        }
    }
    val range = if (parameter.signed) -1f..1f else 0f..1f
    val valueLabel = if (parameter.signed) "%+d".format((value * 4).roundToInt()) else "${(value * 100).roundToInt()}%"
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (parameter == LookParameter.DIFFUSION) "柔光 · 软件模拟" else parameter.label,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(valueLabel, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium)
    }
    val step = if (parameter.signed) .25f else .1f
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", "降低${parameter.label}", value > range.start) { setValue((value - step).coerceIn(range)) }
        Slider(value = value.coerceIn(range), onValueChange = setValue, valueRange = range,
            steps = if (parameter.signed) 7 else 9, modifier = Modifier.weight(1f)
                .semantics { contentDescription = parameter.label })
        StepButton("+", "提高${parameter.label}", value < range.endInclusive) { setValue((value + step).coerceIn(range)) }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (parameter == LookParameter.DIFFUSION) {
            Text(if (settings.hdfEnabled) "全局柔光已开启" else "高光柔化 · 外层光晕",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model::toggleHdr, enabled = true,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "HDR 效果开关"
                    stateDescription = if (settings.hdrEnabled) "开启" else "关闭"
                }) {
                Text(if (settings.hdrEnabled) "HDR · 关" else "HDR", fontSize = 12.sp,
                    color = if (settings.hdrEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = model::toggleHdf, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { contentDescription = "柔光开关" }) {
                Text(if (settings.hdfEnabled) "柔光 · 关" else "柔光", fontSize = 12.sp)
            }
        } else {
            TextButton(onClick = importLut, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (importing) "导入中…" else "导入 LUT", fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = model::resetCurrentLook, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("重置当前影调", fontSize = 12.sp)
            }
        }
    }
    Text(if (parameter == LookParameter.DIFFUSION) "柔光与白平衡对所有预设生效。"
        else if (settings.filmId != null) "胶片 · ${FilmRegistry.find(settings.filmId)?.displayName.orEmpty()} · 独立记忆参数"
        else if (settings.preset == ColorPreset.CUSTOM.name && settings.lutName != null) "LUT · ${settings.lutName}"
        else "每套影调单独记忆参数。",
        Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun WhiteBalanceAdjustments(state: CameraState, settings: AppSettings, model: CameraViewModel) {
    val modes = state.capabilities?.whiteBalanceModes.orEmpty()
    Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        modes.forEach { mode ->
            ControlButton(mode.displayName, state.settings.whiteBalance == mode,
                enabled = state.isReady, onClick = { model.setWhiteBalance(mode) })
        }
        if (modes.isEmpty()) Text("连接相机后显示白平衡选项", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || fontScale > 1.4f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WhiteBalancePad(settings.whiteBalanceAmber, settings.whiteBalanceGreen,
                    { amber, green -> model.setWhiteBalanceShift(amber, green) },
                    Modifier.fillMaxWidth().height(156.dp))
                WhiteBalanceSteps(settings, model, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhiteBalancePad(settings.whiteBalanceAmber, settings.whiteBalanceGreen,
                    { amber, green -> model.setWhiteBalanceShift(amber, green) }, Modifier.weight(1f).height(140.dp))
                WhiteBalanceSteps(settings, model, Modifier.width(148.dp))
            }
        }
    }
    Text("A 暖 · B 冷    G 绿 · M 洋红", Modifier.padding(bottom = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WhiteBalanceSteps(settings: AppSettings, model: CameraViewModel, modifier: Modifier) {
    Column(modifier) {
        DriftAxis("A / B", settings.whiteBalanceAmber, "往蓝偏移", "往琥珀偏移",
            { model.setWhiteBalanceShift(amber = it) })
        DriftAxis("G / M", settings.whiteBalanceGreen, "往洋红偏移", "往绿偏移",
            { model.setWhiteBalanceShift(green = it) })
        TextButton(onClick = model::resetWhiteBalanceShift, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("重置漂移", fontSize = 12.sp)
        }
    }
}

@Composable
private fun WhiteBalancePad(amber: Int, green: Int, onChange: (Int, Int) -> Unit, modifier: Modifier) {
    val latestOnChange by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.semantics {
        contentDescription = "白平衡漂移坐标"
        stateDescription = "琥珀蓝轴 $amber，绿洋红轴 $green"
    }) {
        Canvas(Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(position: Offset) {
                        val inset = 7.dp.toPx()
                        val a = ((position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f) * 18f - 9f)
                            .roundToInt().coerceIn(-9, 9)
                        val g = (9f - (position.y - inset) / (size.height - 2 * inset).coerceAtLeast(1f) * 18f)
                            .roundToInt().coerceIn(-9, 9)
                        latestOnChange(a, g)
                    }
                    update(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { change ->
                            update(change.position)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF244365), Color(0xFF66502F))))
            drawRect(Brush.verticalGradient(listOf(Color(0x5538834E), Color.Transparent, Color(0x555D326A))))
            val inset = 7.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            val height = (size.height - 2 * inset).coerceAtLeast(1f)
            for (i in 0..6) {
                val line = Color.White.copy(alpha = if (i == 3) .4f else .13f)
                val x = inset + width * i / 6
                val y = inset + height * i / 6
                drawLine(line, Offset(x, inset), Offset(x, inset + height), 1.dp.toPx())
                drawLine(line, Offset(inset, y), Offset(inset + width, y), 1.dp.toPx())
            }
            val point = Offset(inset + (amber + 9) / 18f * width, inset + (9 - green) / 18f * height)
            drawCircle(Color.Black.copy(alpha = .6f), 7.dp.toPx(), point)
            drawCircle(Color.White, 5.dp.toPx(), point)
            drawCircle(accent, 2.dp.toPx(), point)
        }
        Text("G", Modifier.align(Alignment.TopCenter), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("M", Modifier.align(Alignment.BottomCenter), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("B", Modifier.align(Alignment.CenterStart), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("A", Modifier.align(Alignment.CenterEnd), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun DriftAxis(label: String, value: Int, minusLabel: String, plusLabel: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", minusLabel, value > -9) { onChange((value - 1).coerceAtLeast(-9)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%+d".format(value), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        StepButton("+", plusLabel, value < 9) { onChange((value + 1).coerceAtMost(9)) }
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)
        .semantics { contentDescription = description }) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
    }
}

@Composable
private fun ShootingAdjustments(state: CameraState, settings: AppSettings, model: CameraViewModel,
    openMascotPicker: () -> Unit, compositionState: CompositionUiState) {
    val controlsRestored by model.controlsRestored.collectAsStateWithLifecycle()
    val capabilities = state.capabilities
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("拍摄参数", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = model::resetShootingParameters, enabled = state.isReady,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "复位拍摄参数" }) { Text("复位", fontSize = 12.sp) }
    }
    Text("复位：自动曝光、EV 归零；设备支持时恢复 AF，保留影调与白平衡。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (capabilities != null && controlsRestored) {
        val exposureMode = state.settings.exposureMode
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ControlButton("P", exposureMode == ExposureMode.PROGRAM, enabled = state.isReady,
                onClick = { model.setExposureMode(ExposureMode.PROGRAM) })
            ControlButton("A", false, enabled = false, onClick = {})
            ControlButton("S", exposureMode == ExposureMode.SHUTTER_PRIORITY,
                enabled = state.isReady && capabilities.supportsShutterPriority,
                onClick = { model.setExposureMode(ExposureMode.SHUTTER_PRIORITY) })
            ControlButton("M", exposureMode == ExposureMode.MANUAL,
                enabled = state.isReady && capabilities.supportsManualExposure,
                onClick = { model.setExposureMode(ExposureMode.MANUAL) })
        }
        Text(when (exposureMode) {
            ExposureMode.PROGRAM -> "P · 快门、ISO 自动"
            ExposureMode.SHUTTER_PRIORITY -> "S · 设定快门，ISO 自动"
            ExposureMode.MANUAL -> "M · 手动设定快门、ISO"
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val fixedAperture = capabilities.fixedAperture
        Text(if (fixedAperture != null) "固定光圈 f/${"%.1f".format(fixedAperture)} · A 档不可用"
            else "当前镜头不支持光圈控制 · A 档不可用",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val minimum = capabilities.exposureCompensationMin
        val maximum = capabilities.exposureCompensationMax
        if (minimum < maximum) {
            val ev = state.settings.exposureCompensationIndex * capabilities.exposureCompensationStepEv
            AdjustmentSlider("曝光补偿", "%+.1f EV".format(ev), state.settings.exposureCompensationIndex.toFloat(),
                minimum.toFloat()..maximum.toFloat(), enabled = exposureMode != ExposureMode.MANUAL,
                steps = (maximum - minimum - 1).coerceAtLeast(0),
                onChange = { model.setExposure(it.roundToInt()) })
        }
        val isoMin = capabilities.isoMin
        val isoMax = capabilities.isoMax
        val timeMin = capabilities.exposureTimeMinNs
        val timeMax = capabilities.exposureTimeMaxNs
        if (exposureMode != ExposureMode.PROGRAM && timeMin != null && timeMax != null) {
            val iso = state.settings.iso ?: 200
            val shutter = state.settings.exposureTimeNs ?: 8_000_000L
            if (exposureMode == ExposureMode.MANUAL && isoMin != null && isoMax != null) {
                AdjustmentSlider("ISO", iso.toString(), iso.toFloat().coerceIn(isoMin.toFloat(), isoMax.toFloat()),
                    isoMin.toFloat()..isoMax.toFloat(), onChange = { model.setManualExposure(it.roundToInt(), shutter) })
            }
            val minLog = kotlin.math.log10(timeMin.toDouble()).toFloat()
            val maxLog = kotlin.math.log10(min(timeMax, 1_000_000_000L).toDouble()).toFloat()
            if (minLog < maxLog) AdjustmentSlider("快门", shutterLabel(shutter),
                kotlin.math.log10(shutter.toDouble()).toFloat().coerceIn(minLog, maxLog), minLog..maxLog,
                onChange = {
                    val exposure = Math.pow(10.0, it.toDouble()).toLong().coerceIn(timeMin, min(timeMax, 1_000_000_000L))
                    if (exposureMode == ExposureMode.SHUTTER_PRIORITY) model.setShutterPriority(exposure)
                    else model.setManualExposure(iso, exposure)
                })
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Text("对焦", style = MaterialTheme.typography.titleSmall)
        val manual = state.settings.focusDistanceDiopters != null
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ControlButton("AF 自动", !manual && capabilities.autoFocusMode != null,
                enabled = state.isReady && capabilities.autoFocusMode != null, onClick = model::autoFocus)
            ControlButton("MF 预设", manual, enabled = state.isReady && capabilities.supportsManualFocus,
                onClick = { model.setManualFocus(settings.focusDiopters) })
        }
        Text(if (manual) "焦点保持预设距离；点 AF 自动恢复自动对焦。"
            else if (capabilities.supportsFocusMetering) "点按画面对焦 · 长按锁定 · 点 AF 解除锁定"
            else "当前设备不支持点按对焦。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (capabilities.supportsManualFocus && (capabilities.minimumFocusDistanceDiopters ?: 0f) > 0f) {
            if (manual) {
                val distance = state.settings.focusDistanceDiopters ?: 0f
                AdjustmentSlider("焦点", if (distance < .01f) "∞" else "%.2f D".format(distance), distance,
                    0f..capabilities.minimumFocusDistanceDiopters!!, onChange = model::setManualFocus)
            }
        }
    }
    ToggleRow("三分网格", settings.grid) { model.toggleGrid() }
    ToggleRow("水平仪", settings.level) { model.toggleLevel() }
    TextButton(onClick = openMascotPicker, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("相机设置", fontSize = 12.sp)
    }
    CompositionAssistantControls(compositionState, settings, model)
    Text("参数自动记忆。音量键可直接拍照。", Modifier.padding(vertical = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CompositionAssistantControls(state: CompositionUiState, settings: AppSettings, model: CameraViewModel) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
    Text("构图辅助 · 实验性", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("推荐构图框", style = MaterialTheme.typography.bodyMedium)
            Text(when (state.status) {
                AssistStatus.OFF -> "关闭，不读取取景画面"
                AssistStatus.UNINSTALLED -> "画面分析可用，AI 模型未安装"
                AssistStatus.PAUSED -> "后台已暂停"
                AssistStatus.LOADING -> "加载本地模型…"
                AssistStatus.READY -> state.statusText ?: "点取景器上的构图，分析当前画面"
                AssistStatus.FAILED -> state.statusText ?: "本地推理失败"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(settings.compositionEnabled, { model.setCompositionEnabled(it) },
            modifier = Modifier.semantics { contentDescription = "构图辅助开关" })
    }
    ToggleRow("实时构图 · 自动更新建议", settings.compositionRealtime, model::setCompositionRealtime)
    Text(compositionTimingLabel(state), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.status == AssistStatus.READY && settings.compositionRealtime) Text(
        "画面分析约每秒更新 · AI 完成后至少间隔 ${formatInferenceTime(state.sampleIntervalMs)}，画面未变则复用",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val modelEntry = dev.streetsnap.app.edge.EdgeModelCatalog.find(settings.compositionModel)
    Text("模型：${modelEntry?.title ?: settings.compositionModel}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        dev.streetsnap.app.edge.EdgeModelCatalog.models.forEach { entry ->
            val chosen = entry.id == settings.compositionModel
            TextButton(onClick = { model.setCompositionModel(entry.id) }, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { selected = chosen; contentDescription = "构图模型：${entry.title}" }) {
                Text(entry.title, fontSize = 11.sp,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    when (state.install) {
        ModelInstallStatus.CHECKING -> Text("检查模型安装状态…", style = MaterialTheme.typography.labelSmall)
        ModelInstallStatus.ABSENT -> TextButton(onClick = model.composition::prepareInstall,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看安装信息") }
        ModelInstallStatus.PREPARING -> Text("读取官方模型清单…", style = MaterialTheme.typography.labelSmall)
        ModelInstallStatus.AVAILABLE -> {
            Text("下载量：${formatBytes(state.bytes ?: 0L)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model.composition::install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("明确安装 ${formatBytes(state.bytes ?: 0L)}")
            }
        }
        ModelInstallStatus.DOWNLOADING -> {
            val total = state.bytes ?: 1L
            val progress = (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下载 ${formatBytes(state.downloadedBytes)} / ${formatBytes(total)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = model.composition::cancelInstall) { Text("取消") }
            }
        }
        ModelInstallStatus.INSTALLED -> {
            Text("已安装 · ${formatBytes(state.bytes ?: 0L)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model.composition::removeModel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("删除本地模型")
            }
        }
        ModelInstallStatus.REMOVING -> Text("删除本地模型…", style = MaterialTheme.typography.labelSmall)
        ModelInstallStatus.FAILED -> {
            Text(state.installError ?: "模型操作失败", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = model.composition::prepareInstall, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
    Text("点“构图”分析一次，也可开启实时。先显示画面分析建议，AI 比较候选后再择优；框仅供取景参考，照片不会自动裁切。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun compositionTimingLabel(state: CompositionUiState): String {
    val started = state.inferenceStartedAtMs
    var elapsedMs by remember(started) { mutableStateOf(0L) }
    LaunchedEffect(started) {
        if (started != null) while (true) {
            elapsedMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            delay(500L)
        }
    }
    val fast = state.fastAdviceMs?.let { "画面分析 ${formatInferenceTime(it)}" }
    val source = when (state.adviceOrigin) {
        AdviceOrigin.IMAGE_ANALYSIS -> "画面分析建议"
        AdviceOrigin.MODEL_SELECTION -> "AI 选择"
        null -> null
    }
    if (started != null) return listOfNotNull(source, fast, "AI 比较中 ${formatInferenceTime(elapsedMs)}").joinToString(" · ")
    if (state.isRequestRunning) return if (source == null) "正在获取构图画面"
        else listOfNotNull(source, fast, "准备 AI 比较").joinToString(" · ")
    val duration = state.modelSelectionMs?.let(::formatInferenceTime)
    val model = when (state.lastOutcome) {
        CompositionOutcome.RETURNED -> duration?.let { "AI 比较 $it" }
        CompositionOutcome.NO_RESULT -> "AI 未明确选择${duration?.let { " · $it" } ?: ""}"
        CompositionOutcome.UNSTABLE_SELECTION -> "AI 比较不稳定${duration?.let { " · $it" } ?: ""}"
        CompositionOutcome.TIMED_OUT -> "AI 比较超时${duration?.let { " · $it" } ?: ""}"
        CompositionOutcome.SCENE_CHANGED -> "画面已变化，旧比较已忽略"
        CompositionOutcome.FAILED -> "AI 暂不可用"
        null -> null
    }
    if (source != null) return listOfNotNull(source, fast, model).joinToString(" · ")
    return when (state.status) {
        AssistStatus.OFF -> "点“构图”获取建议"
        AssistStatus.PAUSED -> "构图辅助已暂停"
        else -> model ?: "点“构图”分析当前画面"
    }
}

private fun formatInferenceTime(milliseconds: Long): String = if (milliseconds >= 1_000L) {
    "%.1f 秒".format(milliseconds / 1_000f)
} else {
    "$milliseconds ms"
}

@Composable
private fun AdjustmentSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueLabel, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (range.start < range.endInclusive) Slider(value.coerceIn(range.start, range.endInclusive), onChange,
            valueRange = range, enabled = enabled, steps = steps)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun CapabilitiesDialog(capabilities: CameraCapabilities?, error: String?, close: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, title = { Text("设备能力") }, confirmButton = {
        TextButton(onClick = close) { Text("完成") }
    }, dismissButton = {
        if (capabilities != null) TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("street-snap 相机能力", capabilities.toJson()))
        }) { Text("复制能力信息") }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            if (capabilities != null) {
                Text("后摄 ID ${capabilities.cameraId}")
                Text("主摄等效：${capabilities.nativeEquivalentFocalLengthMm?.let { "%.1f mm".format(it) } ?: "设备未报告"}")
                Text("可用视角：${capabilities.focalLengthOptions.joinToString { it.label }}")
                Text("手动曝光：${if (capabilities.supportsManualExposure) "支持" else "不支持"}")
                Text("自动对焦：${if (capabilities.autoFocusMode != null) "支持" else "不支持"}")
                Text("点按对焦：${if (capabilities.supportsFocusMetering) "支持" else "不支持"}")
                Text("手动对焦：${if (capabilities.supportsManualFocus) "支持" else "不支持"}")
                Text("白平衡：${capabilities.whiteBalanceModes.joinToString { it.displayName }}")
                Text("对焦校准：${capabilities.focusDistanceCalibration}")
                Text("EV：${capabilities.exposureCompensationMin}…${capabilities.exposureCompensationMax} × ${capabilities.exposureCompensationStepEv}")
                capabilities.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else Text(error ?: "相机连接后显示镜头能力。")
            HorizontalDivider()
            Text("冷启动、取景帧率及荣耀折叠屏表现需要真机验收；此页仅报告系统能力。",
                style = MaterialTheme.typography.bodySmall)
        }
    })
}

private fun shutterLabel(timeNs: Long): String = if (timeNs < 1_000_000_000L)
    "1/${(1_000_000_000.0 / timeNs).roundToInt()} s" else "%.1f s".format(timeNs / 1_000_000_000.0)

/** Normalized crop rectangle eased toward each incoming rule suggestion. */
private data class SmoothCrop(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Holds the four eased crop edges. The values are read inside the Canvas draw scope,
 * so animation ticks trigger a redraw only, not recomposition of the camera screen.
 */
private class SmoothCropState {
    // Order: left, top, right, bottom.
    val edges = Array(4) { Animatable(0f) }
    var ready by mutableStateOf(false)
    var snappedOrigin: AdviceOrigin? = null

    val crop: SmoothCrop?
        get() = if (ready) {
            SmoothCrop(edges[0].value, edges[1].value, edges[2].value, edges[3].value)
        } else {
            null
        }
}

/**
 * Rule advice refreshes several times per second; ease the rectangle so nearby crops
 * glide instead of jumping. The first target, a rule/model handover (origin change),
 * and recovery after the overlay was invalidated snap immediately — we never slide
 * across a zoom/rotation/model change. Degenerate rectangles keep the previous frame.
 */
@Composable
private fun rememberSmoothCrop(overlay: CompositionOverlay?): SmoothCropState {
    val state = remember { SmoothCropState() }
    LaunchedEffect(overlay) {
        val current = overlay
        if (current == null) {
            // Zoom, rotation or model switch invalidated the guide; next guidance snaps.
            state.ready = false
            state.snappedOrigin = null
            return@LaunchedEffect
        }
        val suggestion = current.suggestion
        if (suggestion.right <= suggestion.left || suggestion.bottom <= suggestion.top) return@LaunchedEffect
        val targets = floatArrayOf(suggestion.left, suggestion.top, suggestion.right, suggestion.bottom)
        if (!state.ready || state.snappedOrigin != current.origin) {
            state.edges.forEachIndexed { index, animatable -> animatable.snapTo(targets[index]) }
            state.ready = true
            state.snappedOrigin = current.origin
        } else {
            // Each new overlay cancels the previous tween mid-flight; animate from the
            // current position so 250 ms cadence updates form one continuous glide.
            coroutineScope {
                state.edges.forEachIndexed { index, animatable ->
                    launch {
                        animatable.animateTo(
                            targets[index],
                            tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        )
                    }
                }
            }
        }
    }
    return state
}
