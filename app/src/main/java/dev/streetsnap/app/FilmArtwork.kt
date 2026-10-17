package dev.streetsnap.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import dev.streetsnap.color.FilmProfile

/** Compact, original label studies of real stocks. No scene backgrounds or third-party app sprites. */
@Composable
internal fun FilmArtwork(film: FilmProfile, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / 148f, size.height / 100f)
        withTransform({
            translate((size.width - 148f * scale) / 2f, (size.height - 100f * scale) / 2f)
            scale(scale, scale, Offset.Zero)
        }) {
            val stock = when (film.id) {
                "DAY_01" -> arrayOf("KODAK", "VISION3", "250D", "5207 · DAYLIGHT", "COLOR NEGATIVE FILM")
                "NIGHT_01" -> arrayOf("KODAK", "VISION3", "500T", "5219 · TUNGSTEN", "COLOR NEGATIVE FILM")
                "CHROME_01" -> arrayOf("KODAK", "PROFESSIONAL", "EKTAR 100", "ISO 100 · C-41", "COLOR NEGATIVE FILM")
                "NEG_01" -> arrayOf("KODAK", "VISION3", "50D", "5203 · DAYLIGHT", "COLOR NEGATIVE FILM")
                "PASTEL_01" -> arrayOf("FUJIFILM", "FUJICOLOR", "C200", "ISO 200 · C-41", "COLOR NEGATIVE FILM")
                "MONO_01" -> arrayOf("KODAK", "PROFESSIONAL", "TRI-X 400", "400TX · ISO 400", "BLACK & WHITE FILM")
                else -> arrayOf("KODAK", "PROFESSIONAL", "PORTRA 400", "ISO 400 · C-41", "COLOR NEGATIVE FILM")
            }
            val paper = when (film.id) {
                "NIGHT_01" -> Color(0xFF214C82)
                "PASTEL_01" -> Color(0xFF148F68)
                "CHROME_01" -> Color(0xFFB52228)
                else -> Color(0xFFF1C63B)
            }
            val ink = if (film.id in listOf("NIGHT_01", "PASTEL_01", "CHROME_01")) Color.White else Color(0xFF22221E)
            val band = when (film.id) {
                "MONO_01" -> Color(0xFF24543E)
                "PORTRAIT_01" -> Color(0xFF514333)
                else -> Color(0xFF151817)
            }
            drawRoundRect(Color.Black.copy(alpha = .4f), Offset(4f, 7f), Size(140f, 91f), CornerRadius(8f))
            drawRoundRect(paper, Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
            // Print-like highlights remain subtle; typography and the stock identifier carry the card.
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .15f), Color.Transparent), 3f, 48f),
                Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f))
            drawRect(band, Offset(3f, 31f), Size(140f, 39f))
            fun text(value: String, x: Float, y: Float, fontSize: Float, color: Color, bold: Boolean = false) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = android.graphics.Color.argb((color.alpha * 255).toInt(),
                        (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                    textSize = fontSize
                    typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
                }
                drawContext.canvas.nativeCanvas.drawText(value, x, y, paint)
            }
            text(stock[0], 11f, 17f, 10f, ink, true)
            text(stock[1], 11f, 27f, 6.6f, ink)
            text(stock[2], 11f, 56f, if (stock[2].length > 8) 20f else 24f, Color.White, true)
            text(stock[3], 11f, 80f, 7.5f, ink, true)
            text(stock[4], 11f, 89f, 5f, ink)
            drawRoundRect(Color.White.copy(alpha = .22f), Offset(3f, 3f), Size(140f, 90f), CornerRadius(7f),
                style = Stroke(.7f))
        }
    }
}
