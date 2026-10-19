package dev.streetsnap.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.streetsnap.color.ColorPreset
import dev.streetsnap.color.FilmRegistry

/**
 * Colors for one look/film inside the 35mm frame badge. The frame is a sprocketed
 * filmstrip whose gate carries the look's signature gradient; [accent] is a single
 * small saturated block (kept under a third of the gate per docs/hdr-film-research.md).
 * Monochrome looks use a gray ramp and no chroma; the original look gets three RGB dots.
 * All colors are independently authored design estimates, never sampled assets.
 */
internal data class LookBadgePalette(
    val edge: Color,
    val perf: Color,
    val gateStart: Color,
    val gateEnd: Color,
    val accent: Color? = null,
    val mono: Boolean = false,
    val original: Boolean = false,
) {
    companion object {
        fun of(filmId: String?, preset: ColorPreset): LookBadgePalette {
            when (filmId) {
                "DAY_01" -> // Vision3 250D: warm stock, blue daylight note
                    return LookBadgePalette(Color(0xFF2C2620), Color(0xFFEADFC9),
                        Color(0xFFF0DFC0), Color(0xFF4E7D96), Color(0xFF0E67B4))
                "NIGHT_01" -> // Vision3 500T: deep tungsten blue, tiny factory red
                    return LookBadgePalette(Color(0xFF0E1622), Color(0xFFC9CDD4),
                        Color(0xFF2E4A66), Color(0xFF101C2E), Color(0xFFC8102E))
                "CHROME_01" -> // Ektar 100: gold-orange against dense blue
                    return LookBadgePalette(Color(0xFF20160E), Color(0xFFEFE4CE),
                        Color(0xFFF2A91E), Color(0xFF0F4C8A), Color(0xFFD7261E))
                "NEG_01" -> // Vision3 50D: clean warm daylight, green/blue identity
                    return LookBadgePalette(Color(0xFF1E2A33), Color(0xFFF1E7D6),
                        Color(0xFFE7C79B), Color(0xFF1F6FB2), Color(0xFF2F7D4F))
                "PASTEL_01" -> // Fujicolor C200: pale cool green, red as the smallest dot
                    return LookBadgePalette(Color(0xFF233029), Color(0xFFEAF0EA),
                        Color(0xFFF2F4EF), Color(0xFF0F8A5F), Color(0xFFE60012))
                "MONO_01" -> // TRI-X 400: hard neutral ramp
                    return LookBadgePalette(Color(0xFF0F0F0F), Color(0xFFD8D8D2),
                        Color(0xFFD8D8D2), Color(0xFF3A3A3A), mono = true)
                "PORTRAIT_01" -> // Portra 400: cream into teal
                    return LookBadgePalette(Color(0xFF2A2118), Color(0xFFEFE2CB),
                        Color(0xFFF1E4C9), Color(0xFF157A78), Color(0xFFB97854))
            }
            return when (preset) {
                ColorPreset.ORIGINAL -> LookBadgePalette(Color(0xFF3E4246), Color(0xFFECECE7),
                    Color(0xFFECECE7), Color(0xFFC9CECF), original = true)
                ColorPreset.POSITIVE -> // GR positive: dense slide blue, one slide red
                    LookBadgePalette(Color(0xFF191411), Color(0xFFF4E3C0),
                        Color(0xFF2E5C9A), Color(0xFF14223D), Color(0xFFD23B2A))
                ColorPreset.NEGATIVE -> // faded negative: cream highlight, slate shadow
                    LookBadgePalette(Color(0xFF26221C), Color(0xFFEFE4D2),
                        Color(0xFFEFE4D2), Color(0xFF6E8390), Color(0xFFA14E38))
                ColorPreset.POWDER_BLUE -> LookBadgePalette(Color(0xFF273038), Color(0xFFF6EBD9),
                    Color(0xFFF6EBD9), Color(0xFF8FB2C7))
                ColorPreset.MONO -> LookBadgePalette(Color(0xFF0B0B0B), Color(0xFFF5F5F0),
                    Color(0xFFD8D8D2), Color(0xFF333333), mono = true)
                ColorPreset.SUNNY -> // street sunny: sky blue with a sun-yellow note
                    LookBadgePalette(Color(0xFF20261F), Color(0xFFFFF4D6),
                        Color(0xFFBFE0F2), Color(0xFF5BA3D9), Color(0xFFFFD45E))
                ColorPreset.CLASSIC_CHROME -> LookBadgePalette(Color(0xFF262A28), Color(0xFFC9BFA8),
                    Color(0xFFC9BFA8), Color(0xFF4A5757), Color(0xFF8A6F52))
                ColorPreset.CLASSIC_NEGATIVE -> LookBadgePalette(Color(0xFF23281F), Color(0xFFE8DCC6),
                    Color(0xFFE8DCC6), Color(0xFF5E6E5A), Color(0xFFB89B7E))
                ColorPreset.NOSTALGIC_NEGATIVE -> LookBadgePalette(Color(0xFF241D16), Color(0xFFEFE3C8),
                    Color(0xFFEFE3C8), Color(0xFF6E5A44), Color(0xFFA8875A))
                ColorPreset.ACROS -> LookBadgePalette(Color(0xFF151515), Color(0xFFECECE6),
                    Color(0xFFA8A8A2), Color(0xFF151515), mono = true)
                ColorPreset.LUMIX_NATURAL -> LookBadgePalette(Color(0xFF232624), Color(0xFFE6E6E0),
                    Color(0xFFE6E6E0), Color(0xFF5B605C), Color(0xFF6E8F5E))
                ColorPreset.LUMIX_MONO -> LookBadgePalette(Color(0xFF111414), Color(0xFFE2E7E5),
                    Color(0xFF9AA3A0), Color(0xFF111414), mono = true)
                // Imported/custom LUT: quiet neutral plate, no invented identity color.
                ColorPreset.CUSTOM -> LookBadgePalette(Color(0xFF222528), Color(0xFFCFD4D8),
                    Color(0xFF8A8F94), Color(0xFF3A3E42))
            }
        }
    }
}

/**
 * 48dp always-visible viewfinder badge: a tiny 35mm filmstrip whose gate tints with the
 * active look/film. Shape (sprocketed frame) carries the "film" meaning, color identifies
 * the stock; tap opens the look sheet. No manufacturer artwork or logos are used.
 */
@Composable
internal fun FilmFrameBadge(
    filmId: String?,
    preset: ColorPreset,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LookBadgePalette.of(filmId, preset)
    Box(modifier
        .size(48.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(Color.Black.copy(alpha = .32f))
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "胶片小画：$label，点按调整影调" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp, 27.dp)) { drawFilmstrip(palette) }
    }
}

private fun DrawScope.drawFilmstrip(p: LookBadgePalette) {
    val w = size.width
    val h = size.height
    val rail = h * .24f
    // Film base (warm-dark for color stocks, near-black for mono).
    drawRect(p.edge, size = Size(w, h))
    // Gate: signature gradient, diagonal like a slide frame.
    val gate = Size(w - 5.dp.toPx(), h - 2 * rail)
    val gateLeft = (w - gate.width) / 2f
    drawRect(
        Brush.linearGradient(
            listOf(p.gateStart, p.gateEnd),
            start = Offset(gateLeft, rail),
            end = Offset(gateLeft + gate.width, rail + gate.height),
        ),
        topLeft = Offset(gateLeft, rail), size = gate,
    )
    // Three sprocket holes per rail, kept slightly inset from the strip edges.
    val perfW = 2.6f.dp.toPx()
    val perfH = rail * .56f
    val perfXs = listOf(w * .16f, w * .5f - perfW / 2f, w * .84f - perfW)
    for (x in perfXs) {
        drawRoundRect(p.perf.copy(alpha = .85f), Offset(x, (rail - perfH) / 2f),
            Size(perfW, perfH), androidx.compose.ui.geometry.CornerRadius(perfH * .28f, perfH * .28f))
        drawRoundRect(p.perf.copy(alpha = .85f), Offset(x, h - rail + (rail - perfH) / 2f),
            Size(perfW, perfH), androidx.compose.ui.geometry.CornerRadius(perfH * .28f, perfH * .28f))
    }
    val dot = 2.6f.dp.toPx()
    when {
        p.original -> {
            // Three sub-pixel RGB primaries on the neutral gate: color is available, ungraded.
            val ys = rail + gate.height / 2f - dot / 2f
            val xs = listOf(w * .36f, w * .5f - dot / 2f, w * .64f - dot)
            val rgb = listOf(Color(0xFFD94E3E), Color(0xFFE3B23C), Color(0xFF3E7CB1))
            xs.forEachIndexed { i, x -> drawRect(rgb[i], Offset(x, ys), Size(dot, dot)) }
        }
        p.accent != null && !p.mono -> {
            // One small saturated block in the gate's lower-right: ≤1/9 of gate area.
            drawRect(p.accent,
                Offset(gateLeft + gate.width - dot * 1.9f, rail + gate.height - dot * 1.9f),
                Size(dot, dot))
        }
    }
}
