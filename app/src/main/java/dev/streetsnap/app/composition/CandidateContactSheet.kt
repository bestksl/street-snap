package dev.streetsnap.app.composition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** A/B/C use the list order. The caller owns the returned bitmap and must recycle it. */
internal object CandidateContactSheet {
    fun create(source: Bitmap, candidates: List<CompositionCandidate>): Bitmap {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        require(candidates.size == 3) { "模型排序需要三个裁剪候选" }
        // This always allocates a separate bitmap: an asynchronous model task must
        // not retain the source frame after its camera callback returns.
        val sheet = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(244, 244, 244))
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 30f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 210, 210)
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            candidates.forEachIndexed { index, candidate ->
                val x = (index % 2) * 256f
                val y = (index / 2) * 256f
                val box = candidate.crop
                val left = floor(box.left * source.width).toInt().coerceIn(0, source.width - 1)
                val top = floor(box.top * source.height).toInt().coerceIn(0, source.height - 1)
                val right = ceil(box.right * source.width).toInt().coerceIn(left + 1, source.width)
                val bottom = ceil(box.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                val crop = Rect(left, top, right, bottom)
                val available = RectF(x + 8f, y + 40f, x + 248f, y + 248f)
                val scale = min(available.width() / crop.width(), available.height() / crop.height())
                val width = crop.width() * scale
                val height = crop.height() * scale
                val destination = RectF(available.centerX() - width / 2f, available.centerY() - height / 2f,
                    available.centerX() + width / 2f, available.centerY() + height / 2f)
                canvas.drawBitmap(source, crop, destination, imagePaint)
                canvas.drawRect(destination, borderPaint)
                canvas.drawText(('A'.code + index).toChar().toString(), x + 128f, y + 31f, labelPaint)
            }
            // The fourth quadrant remains empty. No score, highlight or reason
            // reveals which crop the fast advisor preferred to the model.
            return sheet
        } catch (error: Throwable) {
            sheet.recycle()
            throw error
        }
    }
}
