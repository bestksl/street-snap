package dev.streetsnap.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import dev.streetsnap.color.FilmProfile

/**
 * Compact, original label studies of real stocks. No manufacturer artwork, product
 * photos or third-party app sprites are used: only stock names, public color identity
 * and independently authored vector geometry on a fixed 148×100 canvas. The seven cards
 * deliberately differ in silhouette language (cine cartridge+strip / box / dot row /
 * monochrome slab), not just background color, so they stay distinguishable thumbnailed.
 */
@Composable
internal fun FilmArtwork(film: FilmProfile, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / 148f, size.height / 100f)
        withTransform({
            translate((size.width - 148f * scale) / 2f, (size.height - 100f * scale) / 2f)
            scale(scale, scale, Offset.Zero)
        }) {
            val card = Path().apply { addRoundRect(RoundRect(3f, 3f, 143f, 93f, 7f, 7f)) }
            drawRoundRect(Color.Black.copy(alpha = .4f), Offset(4f, 7f), Size(140f, 91f), CornerRadius(8f))
            clipPath(card) {
                when (film.id) {
                    "DAY_01", "NIGHT_01", "NEG_01" -> drawVision3(film)
                    "CHROME_01" -> drawEktar()
                    "PASTEL_01" -> drawC200()
                    "MONO_01" -> drawTriX()
                    else -> drawPortra()
                }
                toneStrip(film)
            }
            drawRoundRect(Color.White.copy(alpha = .22f), Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f),
                style = Stroke(.7f))
        }
    }
}

/* ---------- Vision3 250D / 500T / 50D: black cine magazine, sprocketed film strip.
 * The magazine color follows the production convention reported by loaders/ACs and the
 * official sell sheets: 250D blue, 500T red, 50D green (not a Kodak-published spec). */

private fun DrawScope.drawVision3(film: FilmProfile) {
    val code = when (film.id) {
        "NIGHT_01" -> Color(0xFFC8102E) // 500T tungsten red
        "NEG_01" -> Color(0xFF2E8540)   // 50D daylight green
        else -> Color(0xFF0E67B4)      // 250D daylight blue
    }
    val model = when (film.id) { "NIGHT_01" -> "500T"; "NEG_01" -> "50D"; else -> "250D" }
    val codeNo = when (film.id) { "NIGHT_01" -> "5219"; "NEG_01" -> "5203"; else -> "5207" }
    val edgeCode = when (film.id) { "NIGHT_01" -> "EJ"; "NEG_01" -> "ER"; else -> "EN" }
    val balance = if (film.id == "NIGHT_01") "TUNGSTEN" else "DAYLIGHT"

    drawRoundRect(Color(0xFF111315), Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), Color.Transparent), 3f, 46f),
        Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))

    // cine magazine: flange + body + code-color band + hub, on the left
    drawRoundRect(Color(0xFF050607), Offset(14f, 8f), Size(26f, 7f), CornerRadius(2f))
    drawRoundRect(Color(0xFF050607), Offset(10f, 13f), Size(34f, 49f), CornerRadius(4f))
    drawRect(code, Offset(10f, 20f), Size(34f, 7f))
    label("KODAK", 13.5f, 36.5f, 7.5f, Color.White, bold = true)
    drawCircle(Color(0xFF45484E), 4.4f, Offset(27f, 48f), style = Stroke(1.4f))
    label(codeNo, 10f, 71f, 7.5f, Color(0xFFE9E7DF), bold = true)
    label("($edgeCode)", 10f, 80f, 5.5f, Color(0xFF8C9096))

    // sprocketed film strip on the right
    drawRoundRect(Color(0xFF050607), Offset(52f, 13f), Size(88f, 50f), CornerRadius(2f))
    for (row in 0..4) {
        val y = 16f + row * 9f
        drawRoundRect(Color(0xFFBFC3C9), Offset(55f, y), Size(6f, 4f), CornerRadius(.8f))
        drawRoundRect(Color(0xFFBFC3C9), Offset(131f, y), Size(6f, 4f), CornerRadius(.8f))
    }
    labelCentered("VISION3", 96f, 33f, 9.5f, Color.White, bold = true)
    labelCentered(model, 96f, 52f, 17f, code, bold = true)
    labelCentered(balance, 96f, 60f, 5.5f, Color(0xFFC9CCD1))
}

/* ---------- Ektar 100: gold-orange box, big blue word, white speed plate */

private fun DrawScope.drawEktar() {
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFF4C748), Color(0xFFE8852B))),
        Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
    drawRect(Color(0xFFF6E9C8), Offset(3f, 3f), Size(140f, 13f))
    label("KODAK", 10f, 12.5f, 7.5f, Color(0xFF103A8C), bold = true)
    label("PROFESSIONAL", 63f, 12.5f, 6f, Color(0xFF7A3818))
    label("EKTAR", 9f, 47f, 30f, Color(0xFF103A8C), bold = true)
    drawRoundRect(Color(0xFFF7F3E6), Offset(92f, 50f), Size(42f, 25f), CornerRadius(3f),
        style = Stroke(1f))
    labelCentered("100", 113f, 70f, 22f, Color(0xFF103A8C), bold = true)
    label("COLOR NEGATIVE · C-41", 10f, 81f, 5.5f, Color(0xFF103A8C))
}

/* ---------- Portra 400: warm cream box, teal word, abstract skin-tone swatch row.
 * The word color follows the current 400 box teal (160=orange / 400=teal / 800=violet);
 * the dot row is an original color-language element, not a reproduction of pack art. */

private fun DrawScope.drawPortra() {
    val teal = Color(0xFF157A78)
    drawRoundRect(Color(0xFFF1E4C9), Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .5f), Color.Transparent), 3f, 40f),
        Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
    drawRect(teal, Offset(3f, 3f), Size(140f, 13f))
    label("KODAK", 10f, 12.5f, 7.5f, Color.White, bold = true)
    label("PORTRA", 9f, 41f, 21f, teal, bold = true)
    val skins = listOf(Color(0xFFE9BC98), Color(0xFFD39A78), Color(0xFFB97854))
    skins.forEachIndexed { i, c ->
        drawCircle(c, 7f, Offset(20f + i * 20f, 58f))
        drawCircle(Color.White.copy(alpha = .75f), 7f, Offset(20f + i * 20f, 58f), style = Stroke(1f))
    }
    labelCentered("400", 113f, 67f, 30f, Color(0xFF9A7137), bold = true)
    label("PROFESSIONAL", 10f, 76f, 6.5f, teal)
}

/* ---------- Fujicolor C200 (Asian packaging): green upper / white lower fields split on
 * a diagonal, white FUJICOLOR word, red C200 word. The diagonal split is an original
 * simplification of the pack layout, not copied artwork. */

private fun DrawScope.drawC200() {
    val fujiGreen = Color(0xFF0F8A5F)
    drawRoundRect(Color(0xFFF4F6F2), Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
    val greenField = Path().apply {
        moveTo(3f, 3f)
        lineTo(143f, 3f)
        lineTo(143f, 52f)
        lineTo(3f, 64f)
        close()
    }
    drawPath(greenField, fujiGreen)
    drawRect(Color(0xFFE60012), Offset(3f, 3f), Size(4f, 61f))
    label("FUJIFILM", 12f, 14f, 8f, Color.White, bold = true)
    label("FUJICOLOR", 10f, 34f, 9f, Color.White, bold = true)
    label("DAYLIGHT · C-41", 10f, 49f, 6f, Color(0xFFDCEDE5))
    label("C200", 9f, 81f, 22f, Color(0xFFE60012), bold = true)
    label("ISO 200", 72f, 71f, 7f, Color(0xFF333333), bold = true)
}

/* ---------- Tri-X 400: black slab, heaviest word, grayscale tone strip only */

private fun DrawScope.drawTriX() {
    drawRoundRect(Color(0xFFF2F2EE), Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
    drawRect(Color(0xFF101010), Offset(3f, 3f), Size(140f, 12f))
    label("KODAK", 10f, 12f, 7.5f, Color.White, bold = true)
    label("TRI-X", 8f, 47f, 28f, Color(0xFF101010), bold = true)
    label("400TX · ISO 400", 10f, 61f, 8f, Color(0xFF101010), bold = true)
    label("BLACK & WHITE NEGATIVE", 10f, 74f, 6f, Color(0xFF333333))
}

/* ---------- shared: per-stock tone strip derived from the FilmProfile swatch ---------- */

private fun DrawScope.toneStrip(film: FilmProfile) {
    val steps = 5
    val stepW = 140f / steps
    val start = Color(film.swatchStart)
    val end = Color(film.swatchEnd)
    for (i in 0 until steps) {
        val t = i / (steps - 1f)
        // Swatches are authored per stock; TRI-X carries a neutral gray ramp in its own data.
        val c = lerp(start, end, t)
        drawRect(c, Offset(3f + i * stepW, 85f), Size(stepW + .7f, 8f))
        if (i > 0) drawRect(Color.Black.copy(alpha = .18f), Offset(3f + i * stepW, 85f), Size(.6f, 8f))
    }
}

private fun paintFor(size: Float, color: Color, bold: Boolean): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(), (color.red * 255).toInt(),
            (color.green * 255).toInt(), (color.blue * 255).toInt())
        textSize = size
        typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

private fun DrawScope.label(value: String, x: Float, y: Float, size: Float, color: Color, bold: Boolean = false) {
    drawContext.canvas.nativeCanvas.drawText(value, x, y, paintFor(size, color, bold))
}

private fun DrawScope.labelCentered(value: String, cx: Float, y: Float, size: Float, color: Color, bold: Boolean = false) {
    val paint = paintFor(size, color, bold)
    drawContext.canvas.nativeCanvas.drawText(value, cx - paint.measureText(value) / 2f, y, paint)
}
