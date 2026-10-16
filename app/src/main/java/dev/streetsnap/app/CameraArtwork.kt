package dev.streetsnap.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas

/** Original vector studies of recognisable camera bodies; no downloaded product artwork. */
@Composable
internal fun CameraArtwork(camera: CameraModel, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val factor = minOf(size.width / 180f, size.height / 112f)
        withTransform({
            translate((size.width - 180f * factor) / 2f, (size.height - 112f * factor) / 2f)
            scale(factor, factor, Offset.Zero)
        }) {
            when (camera) {
                CameraModel.RICOH_GR -> ricohGr()
                CameraModel.FUJIFILM_X100VI -> fujiX100()
                CameraModel.SIGMA_FP -> sigmaFp()
                CameraModel.PANASONIC_LX100 -> lumixLx100()
                CameraModel.NIKON_F3 -> nikonF3()
            }
        }
    }
}

private val bodyBlack = Color(0xFF272B2C)
private val bodyEdge = Color(0xFF505659)
private val bodySilver = Color(0xFFC6CBC9)
private val inset = Color(0xFF111516)

private fun DrawScope.panel(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float = 3f) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
}

private fun DrawScope.body(x: Float, y: Float, w: Float, h: Float, radius: Float = 7f) {
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF414748), bodyBlack, Color(0xFF1A1E1F)), y, y + h),
        Offset(x, y), Size(w, h), CornerRadius(radius))
    drawRoundRect(bodyEdge, Offset(x, y), Size(w, h), CornerRadius(radius), style = Stroke(1f))
}

private fun DrawScope.lettering(label: String, x: Float, y: Float, size: Float, color: Color = bodySilver) {
    drawContext.canvas.nativeCanvas.drawText(label, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = size
        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(),
            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
    })
}

private fun DrawScope.lens(x: Float, y: Float, radius: Float, silver: Boolean = false) {
    val c = Offset(x, y)
    drawCircle(Color.Black.copy(alpha = .4f), radius + 3f, c + Offset(1f, 3f))
    drawCircle(if (silver) bodySilver else bodyEdge, radius, c)
    drawCircle(if (silver) Color(0xFF747D7D) else Color(0xFF171C1E), radius - 3f, c)
    drawCircle(if (silver) Color(0xFFBFC6C4) else Color(0xFF42494A), radius - 6f, c,
        style = Stroke(1.3f))
    drawCircle(inset, radius * .65f, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF31535A), Color(0xFF101D29), Color.Black),
        c + Offset(-3f, -3f), radius * .65f), radius * .53f, c)
    drawCircle(Color(0xFF79B5BD).copy(alpha = .6f), radius * .14f, c + Offset(-radius * .19f, -radius * .2f))
    drawCircle(Color(0xFF694C89).copy(alpha = .45f), radius * .08f, c + Offset(radius * .2f, radius * .22f))
    drawCircle(Color(0xFF869494).copy(alpha = .6f), radius * .6f, c, style = Stroke(.75f))
}

private fun DrawScope.ricohGr() {
    panel(34f, 26f, 19f, 5f, bodyEdge)
    panel(127f, 26f, 14f, 5f, bodyEdge)
    body(17f, 31f, 146f, 68f, 8f)
    panel(20f, 39f, 36f, 53f, inset, 7f)
    for (y in 45..87 step 5) drawLine(Color(0xFF343A3A), Offset(24f, y.toFloat()), Offset(49f, y.toFloat()), .65f)
    panel(28f, 34f, 20f, 5f, Color(0xFF697170), 1f)
    panel(119f, 39f, 22f, 6f, Color(0xFF606762), 1f)
    lens(95f, 67f, 27f)
    lettering("GR", 138f, 90f, 10f)
    drawCircle(Color(0xFFB04135), 1.5f, Offset(118f, 45f))
}

private fun DrawScope.fujiX100() {
    panel(35f, 22f, 20f, 8f, bodySilver)
    panel(119f, 20f, 21f, 10f, Color(0xFF929C9B))
    panel(145f, 24f, 8f, 6f, bodySilver)
    body(16f, 30f, 148f, 70f)
    panel(17f, 31f, 146f, 23f, bodySilver, 5f)
    panel(17f, 47f, 146f, 7f, bodySilver, 0f)
    panel(17f, 93f, 146f, 7f, Color(0xFF8C9796), 3f)
    panel(25f, 38f, 26f, 12f, bodyEdge, 2f)
    panel(28f, 40f, 19f, 8f, Color(0xFF21404A), 1f)
    panel(59f, 38f, 16f, 8f, Color(0xFFE1E1CE), 1f)
    panel(149f, 39f, 6f, 10f, inset, 1f)
    lens(106f, 68f, 30f, silver = true)
    lettering("X100VI", 25f, 87f, 7f)
    drawCircle(bodySilver, 4f, Offset(68f, 62f))
    drawLine(Color(0xFFCE352D), Offset(66f, 59f), Offset(71f, 55f), 2f)
}

private fun DrawScope.sigmaFp() {
    panel(31f, 30f, 18f, 4f, bodyEdge, 1f)
    panel(127f, 29f, 13f, 5f, bodyEdge, 2f)
    body(24f, 34f, 131f, 66f, 3f)
    panel(25f, 35f, 17f, 64f, Color(0xFF343A3C), 1f)
    for (x in 28..39 step 3) drawLine(inset, Offset(x.toFloat(), 42f), Offset(x.toFloat(), 94f), 1f)
    drawCircle(bodySilver, 2f, Offset(31f, 39f))
    drawCircle(bodySilver, 2f, Offset(149f, 94f))
    lens(93f, 69f, 32f)
    lettering("fp", 135f, 47f, 11f)
    lettering("SIGMA", 45f, 44f, 6f)
}

private fun DrawScope.lumixLx100() {
    panel(28f, 24f, 21f, 8f, bodyEdge)
    panel(119f, 21f, 22f, 11f, Color(0xFF717A79))
    panel(145f, 25f, 10f, 7f, bodyEdge)
    body(16f, 32f, 148f, 68f)
    panel(20f, 37f, 30f, 58f, inset, 6f)
    panel(61f, 32f, 44f, 8f, Color(0xFF51595A), 2f)
    panel(125f, 39f, 27f, 8f, Color(0xFF717975), 1f)
    lens(96f, 68f, 33f)
    drawCircle(Color(0xFF973A33), 4f, Offset(145f, 73f))
    lettering("LUMIX", 24f, 48f, 7f)
    lettering("LX100", 129f, 92f, 6f)
}

private fun DrawScope.nikonF3() {
    panel(26f, 23f, 22f, 11f, Color(0xFF697171))
    panel(131f, 24f, 20f, 10f, bodyEdge)
    body(14f, 35f, 152f, 66f, 6f)
    val prism = Path().apply {
        moveTo(61f, 40f); lineTo(69f, 16f); lineTo(105f, 16f); lineTo(119f, 40f); close()
    }
    drawPath(prism, Color(0xFF303739))
    drawPath(prism, bodyEdge, style = Stroke(1f))
    panel(17f, 41f, 29f, 56f, inset, 5f)
    panel(20f, 43f, 3f, 45f, Color(0xFFA83A33), 1f)
    panel(133f, 41f, 26f, 8f, Color(0xFF505757), 1f)
    lens(89f, 69f, 31f)
    lettering("Nikon", 70f, 31f, 9f)
    lettering("F3", 139f, 64f, 10f)
}
