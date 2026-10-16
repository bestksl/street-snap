package dev.streetsnap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDateTime

object FilmCompositor {
    /** Owns only the returned allocation; the caller owns and releases input. */
    fun apply(input: Bitmap, spec: FilmRenderSpec, capturedAt: LocalDateTime): Bitmap {
        if (spec.frame == FilmFrame.NONE && spec.stamp == DateStamp.OFF) return input
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val width = input.width.toFloat()
            val height = input.height.toFloat()
            val shortSide = minOf(width, height)
            val layout = FilmLayout.fit(width, height, spec.frame)
            canvas.drawColor(if (spec.frame == FilmFrame.WHITE) Color.WHITE else Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(input, null, RectF(layout.left, layout.top, layout.right, layout.bottom), paint)
            FilmStamp.text(spec.stamp, capturedAt)?.let { text ->
                paint.apply {
                    color = if (spec.frame == FilmFrame.WHITE) Color.BLACK else Color.rgb(255, 193, 112)
                    typeface = Typeface.MONOSPACE
                    textAlign = Paint.Align.RIGHT
                    textSize = shortSide * if (spec.frame == FilmFrame.NONE) .03f else .023f
                    if (spec.frame == FilmFrame.NONE) setShadowLayer(shortSide * .003f, 0f, 0f, Color.BLACK)
                }
                val right = if (spec.frame == FilmFrame.NONE) width - shortSide * .04f else layout.right
                val centerY = if (spec.frame == FilmFrame.NONE) height - shortSide * .05f
                    else (layout.bottom + height) / 2f
                val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(text, right, baseline, paint)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
