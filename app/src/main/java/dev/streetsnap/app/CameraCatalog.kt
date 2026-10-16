package dev.streetsnap.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.streetsnap.color.ColorPreset

internal enum class CameraModel(val brand: String, val model: String) {
    RICOH_GR("理光", "GR"),
    FUJIFILM_X100VI("富士", "X100VI"),
    SIGMA_FP("适马", "fp"),
    PANASONIC_LX100("松下", "LX100"),
    NIKON_F3("尼康", "F3");

    companion object {
        fun fromId(id: String?): CameraModel? = entries.firstOrNull { it.name == id }
    }
}

private enum class CameraLookGroup(val camera: CameraModel?, val presets: List<ColorPreset>) {
    RICOH(CameraModel.RICOH_GR, listOf(ColorPreset.POSITIVE, ColorPreset.NEGATIVE, ColorPreset.MONO)),
    FUJI(CameraModel.FUJIFILM_X100VI, listOf(ColorPreset.CLASSIC_CHROME, ColorPreset.CLASSIC_NEGATIVE,
        ColorPreset.NOSTALGIC_NEGATIVE, ColorPreset.ACROS)),
    SIGMA(CameraModel.SIGMA_FP, listOf(ColorPreset.POWDER_BLUE)),
    LUMIX(CameraModel.PANASONIC_LX100, listOf(ColorPreset.LUMIX_NATURAL, ColorPreset.LUMIX_MONO)),
    GENERAL(null, listOf(ColorPreset.ORIGINAL, ColorPreset.SUNNY, ColorPreset.CUSTOM));

    companion object {
        fun forPreset(id: String) = entries.firstOrNull { group -> group.presets.any { it.name == id } } ?: GENERAL
    }
}

/** Pick a camera family first, then its looks. Browsing a family never changes the live look. */
@Composable
internal fun CameraLookPicker(settings: AppSettings, onPreset: (ColorPreset) -> Unit) {
    var group by remember { mutableStateOf(CameraLookGroup.forPreset(settings.preset)) }
    LaunchedEffect(settings.preset) { group = CameraLookGroup.forPreset(settings.preset) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CameraLookGroup.entries.forEach { item ->
            val chosen = item == group
            val camera = item.camera
            Column(Modifier.width(85.dp).clip(RoundedCornerShape(10.dp))
                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent)
                .border(1.dp, if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .7f)
                    else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { group = item }
                .semantics { selected = chosen; contentDescription = "影调分类：${camera?.brand ?: "通用"}" }
                .padding(horizontal = 4.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (camera != null) CameraArtwork(camera, Modifier.size(76.dp, 48.dp))
                else NeutralLookArtwork(Modifier.size(76.dp, 48.dp))
                Text(camera?.brand ?: "通用", fontSize = 11.sp,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(camera?.model ?: "LUT", fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically) {
        group.presets.filter { it != ColorPreset.CUSTOM || settings.lutName != null }.forEach { preset ->
            val chosen = settings.filmId == null && settings.preset == preset.name
            TextButton(onClick = { onPreset(preset) }, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { selected = chosen; contentDescription = "影调：${preset.displayName}" }) {
                Text(preset.displayName, fontSize = 12.sp,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NeutralLookArtwork(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val radius = size.minDimension * .29f
        drawCircle(color.copy(alpha = .8f), radius, center, style = Stroke(1.5.dp.toPx()))
        drawCircle(color.copy(alpha = .4f), radius * .66f, center, style = Stroke(1.dp.toPx()))
        drawCircle(color.copy(alpha = .5f), radius * .19f, center)
    }
}

@Composable
internal fun MascotPicker(
    selectedId: String?,
    orientation: String,
    onSelect: (String?) -> Unit,
    onOrientation: (String) -> Unit,
    onDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("相机") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraModel.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { camera ->
                            val chosen = selectedId == camera.name
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .border(1.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { onSelect(camera.name); onDismiss() }
                                .semantics { selected = chosen; contentDescription = "摆件：${camera.brand} ${camera.model}" }
                                .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CameraArtwork(camera, Modifier.fillMaxWidth().height(62.dp))
                                Text("${camera.brand} · ${camera.model}", fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("摆件只改变外观，影调单独选择。", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("取景方向", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf("AUTO" to "自动", "LANDSCAPE" to "横持", "PORTRAIT" to "竖持").forEach { (id, label) ->
                        TextButton(onClick = { onOrientation(id) }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { selected = orientation == id; contentDescription = "取景方向：$label" }) {
                            Text(label, color = if (orientation == id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { onDismiss(); onDiagnostics() }, modifier = Modifier.fillMaxWidth()) {
                    Text("设备诊断", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { onSelect(null); onDismiss() }) { Text("不显示") } })
}
