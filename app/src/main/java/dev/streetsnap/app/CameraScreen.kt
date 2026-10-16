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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ViewPort
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Slider
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
import dev.streetsnap.camera.FocalLength
import dev.streetsnap.color.ColorPreset
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    var permitted by remember { mutableStateOf(activity.hasCameraPermission()) }
    var denied by remember { mutableStateOf(false) }
    var adjustmentTab by remember { mutableStateOf<AdjustmentTab?>(null) }
    var showCapabilities by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = adjustmentTab != null && !showCapabilities) { adjustmentTab = null }
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
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(model) {
        model.notices.collect { notice ->
            snackbar.showSnackbar(notice.text, duration = SnackbarDuration.Short)
        }
    }
    val currentReady by rememberUpdatedState(permitted && state.isReady && !showCapabilities)
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
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("途帧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(" / SNAP", Modifier.padding(start = 6.dp), fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showCapabilities = true }) { Text("设备") }
                }
                if (!permitted) {
                    PermissionPanel(denied, { permission.launch(Manifest.permission.CAMERA) }, activity::openAppSettings)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val landscape = maxWidth > maxHeight
                        if (landscape && maxWidth >= 600.dp) {
                            val sideWidth = minOf(356.dp, maxWidth * .48f)
                            Row(Modifier.fillMaxSize()) {
                                Viewfinder(model, state, settings, retry, true,
                                    Modifier.weight(1f).fillMaxHeight(), onRetry = { retry++ })
                                ShootingControls(state, settings, inFlight, loaded, lutLoading, model,
                                    Modifier.width(sideWidth).fillMaxHeight(),
                                    adjustmentTab, { adjustmentTab = it }, sidePanel = true,
                                    panelHeight = 264.dp,
                                    importLut = { lutPicker.launch(arrayOf("*/*")) },
                                    latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                            }
                        } else {
                            val compact = maxHeight < 360.dp
                            val previewHeight = maxOf(120.dp, maxHeight * .45f)
                            val panelHeight = if (compact) 224.dp else (maxHeight - 306.dp).coerceIn(128.dp, 264.dp)
                            // Tiny split-screen windows can scroll instead of losing their viewfinder.
                            Column(if (compact) Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                else Modifier.fillMaxSize()) {
                                Viewfinder(model, state, settings, retry, landscape,
                                    if (compact) Modifier.fillMaxWidth().height(previewHeight)
                                    else Modifier.weight(1f).fillMaxWidth(), onRetry = { retry++ })
                                ShootingControls(state, settings, inFlight, loaded, lutLoading, model,
                                    Modifier.fillMaxWidth(), adjustmentTab, { adjustmentTab = it },
                                    sidePanel = false, panelHeight = panelHeight,
                                    importLut = { lutPicker.launch(arrayOf("*/*")) },
                                    latestPhoto = latestPhoto, openPhoto = activity::openPhoto)
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
    if (showCapabilities) CapabilitiesDialog(state.capabilities, state.error, { showCapabilities = false })
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
    modifier: Modifier,
    onRetry: () -> Unit,
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
    val hasSize = width > 0 && height > 0
    // The adjustment panel only changes the preview's size, not its 4:3 framing.
    // PreviewView handles those resizes without tearing down the camera session.
    LaunchedEffect(preview, lifecycle, retry, hasSize, landscape) {
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
            model.camera.bind(lifecycle, preview.surfaceProvider, model.colors.previewEffect(model::onPreviewError), viewPort, rotation)
            model.onCameraBound()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            bindingError = error.message ?: "相机未能启动"
        }
    }
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = if (landscape) 4f / 3f else 3f / 4f
        val previewWidth = min(maxWidth.value, maxHeight.value * ratio).dp
        val previewHeight = previewWidth / ratio
        Box(Modifier.size(previewWidth, previewHeight)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().onSizeChanged {
                width = it.width
                height = it.height
            })
            val roll = rememberLevelAngle(settings.level, preview)
            Canvas(Modifier.fillMaxSize().pointerInput(preview, state.isReady) {
                detectTapGestures { position ->
                    if (state.isReady) model.focus(preview.meteringPointFactory.createPoint(position.x, position.y))
                }
            }) {
                if (settings.grid) {
                    val color = Color.White.copy(alpha = .32f)
                    for (i in 1..2) {
                        drawLine(color, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                        drawLine(color, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                    }
                }
                if (settings.level && roll != null) {
                    val radians = Math.toRadians(roll.toDouble())
                    val length = min(size.width * .2f, 66.dp.toPx())
                    val delta = Offset((kotlin.math.cos(radians) * length).toFloat(), (kotlin.math.sin(radians) * length).toFloat())
                    val midpoint = center
                    val color = if (abs(roll) < 1.5f) Color(0xFFE2FF79) else Color.White.copy(alpha = .7f)
                    drawLine(color, midpoint - delta, midpoint + delta, 2.dp.toPx())
                    drawCircle(color, 3.dp.toPx(), midpoint)
                }
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.settings.focalLength.label, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(4.dp)).padding(5.dp))
                Spacer(Modifier.weight(1f))
                if (settings.hdfEnabled) Text("HDF", color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(4.dp)).padding(5.dp))
                if (state.settings.focusDistanceDiopters != null) Text("MF", color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(5.dp))
            }
            if (state.isFocusing) Text("对焦中", Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.White)
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
    LOOK("影调"), WHITE_BALANCE("白平衡"), SHOOTING("拍摄"),
}

private enum class LookParameter(val label: String, val signed: Boolean = true) {
    INTENSITY("强度", false), SATURATION("饱和度"), HUE("色相"), CONTRAST("对比度"),
    BRIGHTNESS("亮度"), HIGHLIGHTS("高光"), SHADOWS("阴影"), SHARPNESS("锐度"),
    CLARITY("清晰度"), GRAIN("颗粒", false), VIGNETTE("暗角", false), DIFFUSION("柔光", false),
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
    latestPhoto: Uri?,
    openPhoto: (Uri) -> Unit,
) {
    val focalLengths = listOf(FocalLength.MM28, FocalLength.MM35, FocalLength.MM50)
    val capabilities = state.capabilities
    val supportedFocalLengths = capabilities?.focalLengthOptions.orEmpty()
    val minimumFocalWidth = maxOf(48.dp, (48f * LocalDensity.current.fontScale).dp)
    val focalHint = when {
        capabilities == null -> "焦段信息尚不可用 · 可在“设备”查看详情"
        focalLengths.none { it in supportedFocalLengths } && state.settings.focalLength == FocalLength.NATIVE ->
            if (capabilities.nativeEquivalentFocalLengthMm == null) "焦段信息不可用 · 当前为原生视角"
            else "三档视角暂不可用 · 当前为原生视角"
        focalLengths.any { it !in supportedFocalLengths } -> "灰色视角暂不可用 · 可在“设备”查看详情"
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
        }
        if (focalHint != null) Text(focalHint, Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorPreset.entries.filter { it != ColorPreset.CUSTOM || settings.lutName != null }.forEach { preset ->
                ControlButton(preset.displayName, settings.preset == preset.name,
                    enabled = loaded, onClick = { model.setPreset(preset) })
            }
        }
        if (adjustmentTab != null) {
            AdjustmentPanel(state, settings, importing, model, adjustmentTab, onTab, importLut,
                if (sidePanel) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(panelHeight))
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
                Text(if (state.settings.iso != null) "M · ISO ${state.settings.iso}" else "%+.1f EV".format(ev),
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
    modifier: Modifier,
) {
    val contentScroll = rememberScrollState()
    LaunchedEffect(tab) { contentScroll.scrollTo(0) }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            AdjustmentTab.entries.forEach { item ->
                ControlButton(item.label, item == tab, onClick = { onTab(item) })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(horizontal = 12.dp)) {
            when (tab) {
                AdjustmentTab.LOOK -> LookAdjustments(settings, importing, model, importLut)
                AdjustmentTab.WHITE_BALANCE -> WhiteBalanceAdjustments(state, settings, model)
                AdjustmentTab.SHOOTING -> ShootingAdjustments(state, settings, model)
            }
        }
    }
}

@Composable
private fun LookAdjustments(settings: AppSettings, importing: Boolean, model: CameraViewModel, importLut: () -> Unit) {
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
            Text(if (settings.hdfEnabled) "全局柔光已开启" else "点 HDF 开启柔光",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model::toggleHdf, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (settings.hdfEnabled) "关闭" else "开启")
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
private fun ShootingAdjustments(state: CameraState, settings: AppSettings, model: CameraViewModel) {
    val controlsRestored by model.controlsRestored.collectAsStateWithLifecycle()
    val capabilities = state.capabilities
    if (capabilities != null && controlsRestored) {
        val minimum = capabilities.exposureCompensationMin
        val maximum = capabilities.exposureCompensationMax
        if (minimum < maximum) {
            val ev = state.settings.exposureCompensationIndex * capabilities.exposureCompensationStepEv
            AdjustmentSlider("曝光补偿", "%+.1f EV".format(ev), state.settings.exposureCompensationIndex.toFloat(),
                minimum.toFloat()..maximum.toFloat(), enabled = state.settings.iso == null,
                steps = (maximum - minimum - 1).coerceAtLeast(0),
                onChange = { model.setExposure(it.roundToInt()) })
        }
        if (capabilities.supportsManualFocus && (capabilities.minimumFocusDistanceDiopters ?: 0f) > 0f) {
            val manual = state.settings.focusDistanceDiopters != null
            ToggleRow("预设对焦", manual) { model.setManualFocus(if (it) settings.focusDiopters else null) }
            if (manual) {
                val distance = state.settings.focusDistanceDiopters ?: 0f
                AdjustmentSlider("焦点", if (distance < .01f) "∞" else "%.2f D".format(distance), distance,
                    0f..capabilities.minimumFocusDistanceDiopters!!, onChange = model::setManualFocus)
            }
        }
        val isoMin = capabilities.isoMin
        val isoMax = capabilities.isoMax
        val timeMin = capabilities.exposureTimeMinNs
        val timeMax = capabilities.exposureTimeMaxNs
        if (capabilities.supportsManualExposure && isoMin != null && isoMax != null && timeMin != null && timeMax != null) {
            val manual = state.settings.iso != null
            ToggleRow("手动曝光", manual) {
                if (it) model.setManualExposure(200.coerceIn(isoMin, isoMax), 8_000_000L.coerceIn(timeMin, timeMax))
                else model.setManualExposure(null, null)
            }
            if (manual) {
                val iso = state.settings.iso ?: 200
                val shutter = state.settings.exposureTimeNs ?: 8_000_000L
                AdjustmentSlider("ISO", iso.toString(), iso.toFloat(), isoMin.toFloat()..isoMax.toFloat(),
                    onChange = { model.setManualExposure(it.roundToInt(), shutter) })
                val minLog = kotlin.math.log10(timeMin.toDouble()).toFloat()
                val maxLog = kotlin.math.log10(min(timeMax, 1_000_000_000L).toDouble()).toFloat()
                if (minLog < maxLog) AdjustmentSlider("快门", shutterLabel(shutter),
                    kotlin.math.log10(shutter.toDouble()).toFloat().coerceIn(minLog, maxLog), minLog..maxLog,
                    onChange = {
                        val exposure = Math.pow(10.0, it.toDouble()).toLong().coerceIn(timeMin, min(timeMax, 1_000_000_000L))
                        model.setManualExposure(iso, exposure)
                    })
            }
        }
    }
    ToggleRow("三分网格", settings.grid) { model.toggleGrid() }
    ToggleRow("水平仪", settings.level) { model.toggleLevel() }
    Text("参数自动记忆。音量键可直接拍照。", Modifier.padding(vertical = 10.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
