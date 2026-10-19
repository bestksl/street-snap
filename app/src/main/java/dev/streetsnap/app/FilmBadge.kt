package dev.streetsnap.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.streetsnap.color.ColorPreset

/**
 * Carton artwork family for the film-pack badge. Real film boxes share a layout (a wide
 * signature band plus bold model type) but each maker has its own dress code; the art
 * enum carries that personality without reproducing any manufacturer artwork or logo.
 */
internal enum class PackArt {
    /** Dark cine carton (Kodak Vision3): gold/cool band, light knockout perforations. */
    CINE,
    /** Paper colour-negative carton (Ektar/Portra): cream body, coloured band, maker corner block. */
    COLOR,
    /** Fuji green carton (C200): green body, white band, red corner block. */
    FUJI,
    /** Black-and-white stock (Tri-X): white body, black band, stark contrast. */
    MONO,
    /** Built-in camera look simulation: dark card with a slim gradient stripe. */
    BUILTIN,
    /** Ungraded original look: neutral card, three RGB primaries instead of a band. */
    ORIGINAL,
}

/**
 * Colors for one look/film. Kept as the single identity table (also covered by
 * FilmBadgePaletteTest): the pack carton derives its paper/ink/band from these.
 * [edge] is the dark body colour, [perf] the light "paper"/knockout colour,
 * gateStart/gateEnd the signature gradient, [accent] an optional small maker block.
 * Monochrome looks use a gray ramp and no chroma; the original look gets RGB dots.
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
    val art: PackArt = PackArt.BUILTIN,
) {
    companion object {
        fun of(filmId: String?, preset: ColorPreset): LookBadgePalette {
            when (filmId) {
                "DAY_01" -> // Vision3 250D: warm stock, blue daylight note
                    return LookBadgePalette(Color(0xFF2C2620), Color(0xFFEADFC9),
                        Color(0xFFF0DFC0), Color(0xFF4E7D96), Color(0xFF0E67B4), art = PackArt.CINE)
                "NIGHT_01" -> // Vision3 500T: deep tungsten blue, tiny factory red
                    return LookBadgePalette(Color(0xFF0E1622), Color(0xFFC9CDD4),
                        Color(0xFF2E4A66), Color(0xFF101C2E), Color(0xFFC8102E), art = PackArt.CINE)
                "CHROME_01" -> // Ektar 100: gold-orange against dense blue
                    return LookBadgePalette(Color(0xFF20160E), Color(0xFFEFE4CE),
                        Color(0xFFF2A91E), Color(0xFF0F4C8A), Color(0xFFD7261E), art = PackArt.COLOR)
                "NEG_01" -> // Vision3 50D: clean warm daylight, green/blue identity
                    return LookBadgePalette(Color(0xFF1E2A33), Color(0xFFF1E7D6),
                        Color(0xFFE7C79B), Color(0xFF1F6FB2), Color(0xFF2F7D4F), art = PackArt.CINE)
                "PASTEL_01" -> // Fujicolor C200: pale cool green, red as the smallest dot
                    return LookBadgePalette(Color(0xFF12704C), Color(0xFFEAF0EA),
                        Color(0xFFF2F4EF), Color(0xFF0F8A5F), Color(0xFFE60012), art = PackArt.FUJI)
                "MONO_01" -> // TRI-X 400: hard neutral ramp
                    return LookBadgePalette(Color(0xFF0F0F0F), Color(0xFFF3F2EC),
                        Color(0xFFD8D8D2), Color(0xFF3A3A3A), mono = true, art = PackArt.MONO)
                "PORTRAIT_01" -> // Portra 400: cream into teal
                    return LookBadgePalette(Color(0xFF2A2118), Color(0xFFF4EAD2),
                        Color(0xFFF1E4C9), Color(0xFF157A78), Color(0xFFB97854), art = PackArt.COLOR)
            }
            return when (preset) {
                ColorPreset.ORIGINAL -> LookBadgePalette(Color(0xFF3E4246), Color(0xFFECECE7),
                    Color(0xFFECECE7), Color(0xFFC9CECF), original = true, art = PackArt.ORIGINAL)
                ColorPreset.POSITIVE -> // GR positive: dense slide blue, one slide red
                    LookBadgePalette(Color(0xFF191411), Color(0xFFF4E3C0),
                        Color(0xFF2E5C9A), Color(0xFF14223D), Color(0xFFD23B2A))
                ColorPreset.NEGATIVE -> // faded negative: cream highlight, slate shadow
                    LookBadgePalette(Color(0xFF26221C), Color(0xFFEFE4D2),
                        Color(0xFFEFE4D2), Color(0xFF6E8390), Color(0xFFA14E38))
                ColorPreset.POWDER_BLUE -> LookBadgePalette(Color(0xFF273038), Color(0xFFF6EBD9),
                    Color(0xFFF6EBD9), Color(0xFF8FB2C7))
                ColorPreset.MONO -> LookBadgePalette(Color(0xFF0B0B0B), Color(0xFFF5F5F0),
                    Color(0xFFD8D8D2), Color(0xFF333333), mono = true, art = PackArt.MONO)
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
                    Color(0xFFA8A8A2), Color(0xFF151515), mono = true, art = PackArt.MONO)
                ColorPreset.LUMIX_NATURAL -> LookBadgePalette(Color(0xFF232624), Color(0xFFE6E6E0),
                    Color(0xFFE6E6E0), Color(0xFF5B605C), Color(0xFF6E8F5E))
                ColorPreset.LUMIX_MONO -> LookBadgePalette(Color(0xFF111414), Color(0xFFE2E7E5),
                    Color(0xFF9AA3A0), Color(0xFF111414), mono = true, art = PackArt.MONO)
                // Imported/custom LUT: quiet neutral plate, no invented identity color.
                ColorPreset.CUSTOM -> LookBadgePalette(Color(0xFF222528), Color(0xFFCFD4D8),
                    Color(0xFF8A8F94), Color(0xFF3A3E42))
            }
        }
    }
}

/**
 * The actual type printed on the pack: a small descriptor line plus two lines of model
 * type. Boxed films show their real stock designation; built-in presets are labelled as
 * in-camera simulations so the badge never implies physical film it cannot be.
 */
internal data class FilmPackCopy(val tag: String, val line1: String, val line2: String) {
    companion object {
        fun of(filmId: String?, preset: ColorPreset): FilmPackCopy = when (filmId) {
            "DAY_01" -> FilmPackCopy("KODAK · CINE", "VISION3", "250D")
            "NIGHT_01" -> FilmPackCopy("KODAK · CINE", "VISION3", "500T")
            "NEG_01" -> FilmPackCopy("KODAK · CINE", "VISION3", "50D")
            "CHROME_01" -> FilmPackCopy("KODAK · COLOR", "EKTAR", "100")
            "PORTRAIT_01" -> FilmPackCopy("KODAK · COLOR", "PORTRA", "400")
            "PASTEL_01" -> FilmPackCopy("FUJI · COLOR", "FUJICOLOR", "C200")
            "MONO_01" -> FilmPackCopy("KODAK · B&W", "TRI-X", "400")
            else -> when (preset) {
                ColorPreset.ORIGINAL -> FilmPackCopy("IN-CAMERA", "原色", "ORIGINAL")
                ColorPreset.POSITIVE -> FilmPackCopy("IN-CAMERA SIM", "正片", "POSITIVE")
                ColorPreset.NEGATIVE -> FilmPackCopy("IN-CAMERA SIM", "负片", "NEGATIVE")
                ColorPreset.POWDER_BLUE -> FilmPackCopy("IN-CAMERA SIM", "粉末蓝", "POWDER")
                ColorPreset.MONO -> FilmPackCopy("IN-CAMERA SIM", "高反差", "MONO")
                ColorPreset.SUNNY -> FilmPackCopy("IN-CAMERA SIM", "晴街", "SUNNY")
                ColorPreset.CLASSIC_CHROME -> FilmPackCopy("IN-CAMERA SIM", "经典正片", "CC")
                ColorPreset.CLASSIC_NEGATIVE -> FilmPackCopy("IN-CAMERA SIM", "经典负片", "NC")
                ColorPreset.NOSTALGIC_NEGATIVE -> FilmPackCopy("IN-CAMERA SIM", "怀旧负片", "NA")
                ColorPreset.ACROS -> FilmPackCopy("IN-CAMERA SIM", "ACROS", "黑白")
                ColorPreset.LUMIX_NATURAL -> FilmPackCopy("LUMIX · IN-CAM", "自然", "NATURAL")
                ColorPreset.LUMIX_MONO -> FilmPackCopy("LUMIX · IN-CAM", "单色", "MONO")
                ColorPreset.CUSTOM -> FilmPackCopy("IMPORTED LUT", "自定义", "CUSTOM")
            }
        }
    }
}

/**
 * Always-visible viewfinder badge drawn as a tiny film carton: signature band on the
 * left, bold model type on the right, dressed per stock family. Shape and type carry
 * the "film" meaning; the exact model designation is always printed. No manufacturer
 * artwork or logos are used — every pixel is independently authored.
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
    val copy = FilmPackCopy.of(filmId, preset)
    val darkCarton = palette.art == PackArt.CINE || palette.art == PackArt.BUILTIN
    val paper = when (palette.art) {
        PackArt.COLOR -> palette.perf
        PackArt.FUJI -> palette.gateEnd
        PackArt.MONO -> palette.perf
        PackArt.ORIGINAL -> Color(0xFFECE8DE)
        PackArt.CINE, PackArt.BUILTIN -> palette.edge
    }
    val ink = if (darkCarton || palette.art == PackArt.FUJI) Color(0xFFF6F2E9) else Color(0xFF1C1A17)
    Box(modifier
        .size(width = 84.dp, height = 50.dp)
        .clip(RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "胶片包装：$label，点按调整影调" }) {
        Canvas(Modifier.size(width = 84.dp, height = 50.dp)) {
            drawCarton(palette, paper)
        }
        // includeFontPadding=false is load-bearing: three lines inside a 50dp carton would
        // otherwise overflow from the font's built-in top/bottom bearings.
        val tight = androidx.compose.ui.text.TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false))
        // The cine carton prints its maker line in foil gold; everyone else stays quiet ink.
        val tagColor = when (palette.art) {
            PackArt.CINE -> Color(0xFFD9BC7E)
            PackArt.FUJI -> ink.copy(alpha = .82f)
            else -> ink.copy(alpha = .6f)
        }
        val tagStyle = tight.copy(letterSpacing = 0.5.sp)
        Column(Modifier.padding(start = 21.dp, end = 5.dp, top = 5.dp, bottom = 4.dp)) {
            Text(copy.tag, color = tagColor, fontSize = 6.5.sp, style = tagStyle,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(copy.line1, color = ink, fontSize = 10.sp, lineHeight = 12.sp, style = tight,
                fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(copy.line2, color = ink.copy(alpha = .88f), fontSize = 9.sp, lineHeight = 11.sp,
                style = tight, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private fun DrawScope.drawCarton(p: LookBadgePalette, paper: Color) {
    val w = size.width
    val h = size.height
    val r = 10.dp.toPx()
    val radius = CornerRadius(r, r)
    // Clip the whole carton to its outer rounded rectangle, so the square rail
    // stays square on its inner edge while the outer edge follows the rounding.
    clipPath(Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, radius))
    }) {
        drawRect(paper)
        // Subtle top sheen / bottom shade gives the flat paper a printed-carton body
        // instead of one dead fill (the old card read as a flat tagged rectangle).
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = .07f),
                0.18f to Color.White.copy(alpha = 0f),
                0.82f to Color.Black.copy(alpha = 0f),
                1f to Color.Black.copy(alpha = .16f)),
            topLeft = Offset.Zero, size = Size(w, h))
        val rail = 15.dp.toPx()
        // The left rail is a strip of 35mm film: a dressed edge rail with light
        // passing through regular perforations. This is the strongest "film" cue at
        // 84x50dp and replaces the old gradient swatch, whose hard seam read as a tag.
        val (railTop, railBottom) = when (p.art) {
            PackArt.CINE -> Color(0xFF7A6034) to Color(0xFF3C2F18)       // aged gold foil
            PackArt.BUILTIN -> Color(0xFF3B4044) to Color(0xFF222528)    // neutral graphite
            PackArt.COLOR -> p.gateStart to p.gateEnd                    // maker colour
            PackArt.FUJI -> Color(0xFFF4F6F0) to Color(0xFFE3E9DF)       // white rail on green
            PackArt.MONO -> Color(0xFF262626) to Color(0xFF080808)       // black rail on white
            PackArt.ORIGINAL -> Color(0xFFD9D5CA) to Color(0xFFC4C0B6)
        }
        drawRect(Brush.verticalGradient(listOf(railTop, railBottom)),
            topLeft = Offset(0f, 0f), size = Size(rail, h))
        // Hairline metallic inner edge separates rail from the carton face.
        val edgeColor = when (p.art) {
            PackArt.CINE -> Color(0xFFE7CF9B)
            PackArt.COLOR, PackArt.MONO -> Color.Black
            PackArt.FUJI -> Color(0xFF0B5138)
            else -> Color.White
        }
        drawRect(edgeColor.copy(alpha = if (p.art == PackArt.FUJI) .35f else .22f),
            topLeft = Offset(rail - 0.75f.dp.toPx(), 0f),
            size = Size(0.75f.dp.toPx(), h))
        if (p.art == PackArt.ORIGINAL) {
            // Three RGB primaries stacked on the neutral rail: colour available, ungraded.
            val dot = 4.2f.dp.toPx()
            val rgb = listOf(Color(0xFFD94E3E), Color(0xFFE3B23C), Color(0xFF3E7CB1))
            for ((i, c) in rgb.withIndex()) {
                drawCircle(c, dot / 2f, Offset(rail / 2f, h * (0.30f + i * 0.20f)))
            }
        } else {
            // Perforations: five rounded slots with light behind them, like a film strip.
            val holeW = 6.dp.toPx()
            val hole = 3.2f.dp.toPx()
            val holeColor = when (p.art) {
                PackArt.CINE -> Color(0xFFF3E6C2)
                PackArt.BUILTIN -> Color(0xFFD7DBDE)
                PackArt.COLOR -> p.perf
                PackArt.FUJI -> p.gateEnd
                PackArt.MONO -> p.perf
                else -> paper
            }
            val hr = CornerRadius(hole * .34f, hole * .34f)
            for (i in 0..4) {
                val cy = h * (0.15f + i * 0.175f)
                drawRoundRect(holeColor.copy(alpha = .92f),
                    Offset(rail / 2f - holeW / 2f, cy - hole / 2f), Size(holeW, hole), hr)
            }
        }
        // Carton lid seam: a faint horizontal break near the top of the face only.
        drawRect(Color.Black.copy(alpha = .10f),
            topLeft = Offset(rail, 0f), size = Size(w - rail, 0.75f.dp.toPx()))
        // Small maker-colour block in the upper-right corner (cream/green cartons only).
        if (p.art == PackArt.COLOR || p.art == PackArt.FUJI) {
            p.accent?.let { accent ->
                val s = 8.dp.toPx()
                drawPath(Path().apply {
                    moveTo(w - s, 0f)
                    lineTo(w, 0f)
                    lineTo(w, s)
                    close()
                }, accent)
            }
        }
    }
    // Hairline border keeps light cartons readable over bright viewfinder content.
    drawRoundRect(
        Color.Black.copy(alpha = .28f),
        cornerRadius = radius, style = Stroke(width = 1.dp.toPx()),
    )
}
