package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A score ranks image-statistic crop proposals; it is not a subject-detection confidence. */
internal data class CompositionCandidate(
    val crop: NormalizedBoundingBox,
    val score: Float,
    val reason: String,
    /** Featureless frames keep a conservative rule suggestion and skip model comparison. */
    val supportsModelComparison: Boolean = true,
)

/** Content-aware crop suggestions from colour contrast and edges, with no model or Android API. */
internal object FastCompositionAdvisor {
    fun suggest(width: Int, height: Int, argb: IntArray): List<CompositionCandidate> {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
        val factor = min(1.0, 96.0 / max(width, height))
        val w = max(1, (width * factor).roundToInt())
        val h = max(1, (height * factor).roundToInt())
        val pixels = IntArray(w * h) { index ->
            val x = min(width - 1, ((index % w + 0.5) * width / w).toInt())
            val y = min(height - 1, ((index / w + 0.5) * height / h).toInt())
            argb[y * width + x]
        }
        val medians = intArrayOf(16, 8, 0).map { shift ->
            val histogram = IntArray(256)
            pixels.forEach { histogram[it shr shift and 255]++ }
            var count = 0
            histogram.indexOfFirst { count += it; count > pixels.size / 2 }
        }
        val background = (medians[0] shl 16) or (medians[1] shl 8) or medians[2]
        val edges = DoubleArray(pixels.size)
        val signal = DoubleArray(pixels.size) { index ->
            val x = index % w
            val y = index / w
            val edge = (colourDifference(pixels[y * w + max(0, x - 1)], pixels[y * w + min(w - 1, x + 1)]) +
                colourDifference(pixels[max(0, y - 1) * w + x], pixels[min(h - 1, y + 1) * w + x])) / 2
            edges[index] = edge
            val contrast = max(0.0, colourDifference(pixels[index], background) - 0.04)
            contrast.pow(1.4) * 0.65 + max(0.0, edge - 0.025) * 0.8
        }
        val total = signal.sum()
        if (total / pixels.size < 0.004) return conservativeCrops()

        val energy = Integral(w, h, signal)
        val horizontalMoment = Integral(w, h, DoubleArray(signal.size) { signal[it] * (it % w + 0.5) / w })
        val verticalMoment = Integral(w, h, DoubleArray(signal.size) { signal[it] * (it / w + 0.5) / h })
        val edgeEnergy = Integral(w, h, edges)
        val totalEdges = max(1e-9, edges.sum())
        val centerX = horizontalMoment.sum(0, 0, w, h) / total
        val centerY = verticalMoment.sum(0, 0, w, h) / total
        val ranked = mutableListOf<ScoredCrop>()

        for (scale in listOf(0.68, 0.76, 0.84, 0.92, 1.0)) {
            fun positions(center: Double): List<Double> =
                ((0..6).map { (1.0 - scale) * it / 6 } +
                    listOf(1.0 / 3, 0.5, 2.0 / 3).map { (center - scale * it).coerceIn(0.0, 1.0 - scale) }).distinct()
            for (top in positions(centerY)) for (left in positions(centerX)) {
                val x0 = (left * w).roundToInt().coerceIn(0, w - 1)
                val y0 = (top * h).roundToInt().coerceIn(0, h - 1)
                val x1 = ((left + scale) * w).roundToInt().coerceIn(x0 + 1, w)
                val y1 = ((top + scale) * h).roundToInt().coerceIn(y0 + 1, h)
                val retained = energy.sum(x0, y0, x1, y1)
                if (retained <= 0.0) continue
                val u = ((horizontalMoment.sum(x0, y0, x1, y1) / retained - left) / scale).coerceIn(0.0, 1.0)
                val v = ((verticalMoment.sum(x0, y0, x1, y1) / retained - top) / scale).coerceIn(0.0, 1.0)
                val thirds = 1.0 - (hypot(distanceToThird(u), distanceToThird(v)) / 0.24).coerceAtMost(1.0)
                val balance = 1.0 - hypot(u - 0.5, v - 0.5) / 0.7072
                // Border bands include both sides of a proposed cut, but not existing image borders.
                var severedEdges = 0.0
                if (x0 > 0) severedEdges += edgeEnergy.sum(x0 - 1, y0, min(w, x0 + 2), y1)
                if (x1 < w) severedEdges += edgeEnergy.sum(max(0, x1 - 2), y0, x1 + 1, y1)
                if (y0 > 0) severedEdges += edgeEnergy.sum(x0, y0 - 1, x1, min(h, y0 + 2))
                if (y1 < h) severedEdges += edgeEnergy.sum(x0, max(0, y1 - 2), x1, y1 + 1)
                val concentration = (1.0 - scale) / 0.32
                val score = (0.64 * retained / total + 0.14 * thirds + 0.06 * balance +
                    0.16 * concentration - 0.22 * severedEdges / totalEdges).coerceIn(0.0, 1.0)
                ranked += ScoredCrop(left, top, scale, score, u, v)
            }
        }
        val selected = mutableListOf<ScoredCrop>()
        for (crop in ranked.sortedByDescending { it.score }) {
            if (selected.all { intersectionOverUnion(crop, it) < 0.91 }) selected += crop
            if (selected.size == 3) break
        }
        return selected.map { crop ->
            val reason = when {
                crop.scale >= 0.92 -> "保留更多环境与明暗结构，避免过度裁切"
                crop.u < 0.44 -> "让视觉重点靠近左侧三分区，减少周围空白"
                crop.u > 0.56 -> "让视觉重点靠近右侧三分区，减少周围空白"
                crop.v < 0.44 -> "让视觉重点靠近上方三分区，平衡画面留白"
                crop.v > 0.56 -> "让视觉重点靠近下方三分区，平衡画面留白"
                else -> "保留主要对比区域，适度收紧画面"
            }
            CompositionCandidate(crop.rectangle(), crop.score.toFloat(), reason)
        }
    }

    private fun conservativeCrops(): List<CompositionCandidate> = listOf(1f, 0.96f, 0.90f).mapIndexed { index, scale ->
        val inset = (1f - scale) / 2f
        CompositionCandidate(NormalizedBoundingBox(inset, inset, inset + scale, inset + scale),
            0.5f - index * 0.05f, if (index == 0) "纹理与对比较少，保留完整画面更稳妥" else "视觉重点不明显，仅作轻微裁切参考",
            supportsModelComparison = false)
    }

    private fun colourDifference(a: Int, b: Int): Double =
        (abs((a shr 16 and 255) - (b shr 16 and 255)) +
            abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))) / 765.0

    private fun distanceToThird(value: Double): Double = min(abs(value - 1.0 / 3), abs(value - 2.0 / 3))

    private data class ScoredCrop(val left: Double, val top: Double, val scale: Double,
        val score: Double, val u: Double, val v: Double) {
        fun rectangle() = NormalizedBoundingBox(left.toFloat(), top.toFloat(),
            (left + scale).toFloat(), (top + scale).toFloat())
    }

    private fun intersectionOverUnion(a: ScoredCrop, b: ScoredCrop): Double {
        val overlap = max(0.0, min(a.left + a.scale, b.left + b.scale) - max(a.left, b.left)) *
            max(0.0, min(a.top + a.scale, b.top + b.scale) - max(a.top, b.top))
        return overlap / (a.scale * a.scale + b.scale * b.scale - overlap)
    }

    private class Integral(width: Int, height: Int, values: DoubleArray) {
        private val stride = width + 1
        private val data = DoubleArray(stride * (height + 1))
        init {
            for (y in 0 until height) {
                var row = 0.0
                for (x in 0 until width) {
                    row += values[y * width + x]
                    data[(y + 1) * stride + x + 1] = data[y * stride + x + 1] + row
                }
            }
        }
        fun sum(left: Int, top: Int, right: Int, bottom: Int): Double =
            data[bottom * stride + right] - data[top * stride + right] -
                data[bottom * stride + left] + data[top * stride + left]
    }
}
