package dev.streetsnap.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.streetsnap.color.FilmProfile
import dev.streetsnap.color.FilmRegistry

@Composable
internal fun FilmAdjustments(settings: AppSettings, model: CameraViewModel) {
    val active = FilmRegistry.find(settings.filmId)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("胶片配置", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { model.setFilm(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (active == null) "未装卷" else "卸下胶片", fontSize = 12.sp)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            maxWidth / fontScale < 240.dp -> 2
            maxWidth / fontScale < 420.dp -> 3
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilmRegistry.entries.chunked(columns).forEach { films ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    films.forEach { film ->
                        FilmCard(film, active?.id == film.id, Modifier.weight(1f)) { model.setFilm(film.id) }
                    }
                    repeat(columns - films.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Text(active?.description ?: "选择一卷；卸下后恢复先前影调。", Modifier.padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("边框配置", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilmFrame.entries.forEach { frame ->
            val selected = settings.filmFrame == frame.id
            Column(Modifier.width(64.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { model.setFilmFrame(frame) }
                .semantics { this.selected = selected; contentDescription = "边框：${frame.label}" }
                .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(46.dp, 56.dp)) {
                    val layout = FilmLayout.fit(size.width, size.height, frame)
                    drawRect(if (frame == FilmFrame.WHITE) Color.White else Color.Black)
                    drawRect(Brush.linearGradient(listOf(Color(0xFF658B95), Color(0xFFCFB48F))),
                        Offset(layout.left, layout.top), Size(layout.right - layout.left, layout.bottom - layout.top))
                }
                Text(frame.label, Modifier.padding(top = 5.dp), fontSize = 11.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text("日期打印", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        DateStamp.entries.forEach { stamp ->
            val selected = settings.dateStamp == stamp.id
            TextButton(onClick = { model.setDateStamp(stamp) }, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { this.selected = selected }) {
                Text(stamp.label, color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
    Text("边框、日期写入成片。影调里可调颗粒、饱和度等，每卷单独记忆。",
        Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FilmCard(film: FilmProfile, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected; contentDescription = "胶片：${film.displayName}" }) {
        Box(Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(film.swatchStart), Color(film.swatchEnd))))
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .15f), RoundedCornerShape(10.dp))) {
            Text(film.displayName, Modifier.align(Alignment.Center).fillMaxWidth()
                .background(Color.Black.copy(alpha = .85f)).padding(horizontal = 5.dp, vertical = 5.dp),
                color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Text("✓", Modifier.align(Alignment.BottomEnd).padding(horizontal = 5.dp),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
