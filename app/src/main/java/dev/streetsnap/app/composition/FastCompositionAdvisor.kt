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
    /** Which composition language the suggestion copy uses. */
    val style: CompositionStyle = CompositionStyle.GENERIC,
)

/** Crop proposals plus scene readings the UI layer may use (e.g. a rare focal-length nudge). */
internal data class FastCompositionResult(
    val candidates: List<CompositionCandidate>,
    /**
     * A small, concentrated content mass well inside the frame reads as a far-away subject.
     * This is an image-statistic hint, not person detection, and must stay conservative.
     */
    val subjectLooksDistant: Boolean,
)

/**
 * Content-aware crop suggestions from colour contrast, edges and composition geometry,
 * with no model and no Android API. Besides the retained-energy crop score, a small
 * "photographic eye" detector reads symmetry, edge directions, border bands and energy
 * envelopes so the copy names distinct composition styles instead of one fixed sentence.
 *
 * Style affinities are evidence only: every value is a measured signal times geometric
 * fit. No style gets a hand-tuned prior probability, so labels follow what the frame shows.
 */
internal object FastCompositionAdvisor {
    fun suggest(width: Int, height: Int, argb: IntArray): FastCompositionResult {
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
        val medianLuminance = (medians[0] * 299 + medians[1] * 587 + medians[2] * 114) / 255_000.0
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
        if (total / pixels.size < 0.004 || w < 8 || h < 8) {
            return FastCompositionResult(conservativeCrops(medianLuminance), subjectLooksDistant = false)
        }

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
        if (selected.isEmpty()) {
            return FastCompositionResult(conservativeCrops(medianLuminance), subjectLooksDistant = false)
        }

        val features = SceneFeatures.extract(w, h, pixels, signal, energy)
        val subjectLooksDistant = detectDistantSubject(w, signal, selected.first())
        val styled = assignStyles(selected, features).map { (crop, style) ->
            CompositionCandidate(crop.rectangle(), crop.score.toFloat(), describe(style, crop, features), true, style)
        }
        return FastCompositionResult(styled, subjectLooksDistant)
    }

    /** A tight inner-grid cell holding a large share of all energy reads as a far, small subject. */
    private fun detectDistantSubject(w: Int, signal: DoubleArray, topCrop: ScoredCrop): Boolean {
        if (topCrop.scale > 0.88) return false
        val h = signal.size / w
        val grid = 6
        var maxShare = 0.0
        var innerPeak = false
        for (gy in 0 until grid) for (gx in 0 until grid) {
            var cellEnergy = 0.0
            val x0 = (gx * w / grid); val x1 = ((gx + 1) * w / grid)
            val y0 = (gy * h / grid); val y1 = ((gy + 1) * h / grid)
            for (y in y0 until y1) for (x in x0 until x1) cellEnergy += signal[y * w + x]
            val share = cellEnergy / max(1e-9, signal.sum())
            if (share > maxShare) {
                maxShare = share
                innerPeak = gx in 1..grid - 2 && gy in 1..grid - 2
            }
        }
        return innerPeak && maxShare >= 0.24
    }

    /** Greedy style assignment: the top crop claims the best-supported style first. */
    private fun assignStyles(selected: List<ScoredCrop>, features: SceneFeatures): List<Pair<ScoredCrop, CompositionStyle>> {
        val used = HashSet<CompositionStyle>()
        return selected.mapIndexed { index, crop ->
            val ranked = affinities(crop, features).entries
                .filter { it.value > 0.0 }
                .sortedByDescending { it.value }
            val choice = ranked.firstOrNull { index == 0 || it.key !in used }?.key
                ?: ranked.firstOrNull()?.key
                ?: CompositionStyle.GENERIC
            used += choice
            crop to choice
        }
    }

    /**
     * Evidence-only per-crop style scores. There is deliberately no base/prior term:
     * a style can win only on measured scene evidence combined with geometric fit.
     */
    private fun affinities(crop: ScoredCrop, f: SceneFeatures): Map<CompositionStyle, Double> {
        val u = crop.u
        val v = crop.v
        val scale = crop.scale
        val thirdsGeometry = (1 - hypot(distanceToThird(u), distanceToThird(v)) / 0.24).coerceIn(0.0, 1.0)
        val diagonalGeometry = maxOf(
            (1 - abs(v - u) / 0.35).coerceIn(0.0, 1.0),
            (1 - abs(v - (1 - u)) / 0.35).coerceIn(0.0, 1.0),
        )
        return mapOf(
            CompositionStyle.THIRDS to thirdsGeometry,
            CompositionStyle.CENTERED to f.centered *
                (1 - hypot(u - 0.5, v - 0.5) / 0.35).coerceIn(0.0, 1.0),
            CompositionStyle.DIAGONAL to f.diagonal *
                ((scale - 0.72) / 0.14).coerceIn(0.0, 1.0) * (0.4 + 0.6 * diagonalGeometry),
            CompositionStyle.LEADING_LINES to f.leading *
                ((scale - 0.80) / 0.12).coerceIn(0.0, 1.0),
            CompositionStyle.TRIANGLE to f.triangle *
                ((scale - 0.70) / 0.20).coerceIn(0.0, 1.0) *
                (1 - abs(v - 0.58) / 0.30).coerceIn(0.0, 1.0),
            CompositionStyle.FRAME to f.frame *
                (1 - abs(scale - 0.88) / 0.12).coerceIn(0.0, 1.0),
            CompositionStyle.FOREGROUND_DEPTH to maxOf(
                f.foreground[0] * ((scale - 0.80) / 0.12).coerceIn(0.0, 1.0) *
                    ((v - 0.42) / 0.30).coerceIn(0.0, 1.0),
                f.foreground[1] * ((scale - 0.80) / 0.12).coerceIn(0.0, 1.0) *
                    ((0.58 - v) / 0.30).coerceIn(0.0, 1.0),
            ),
            CompositionStyle.NEGATIVE_SPACE to maxOf(
                f.negSpace[0] * ((0.55 - u) / 0.35).coerceIn(0.0, 1.0),
                f.negSpace[1] * ((u - 0.45) / 0.35).coerceIn(0.0, 1.0),
                f.negSpace[2] * ((0.55 - v) / 0.35).coerceIn(0.0, 1.0),
                f.negSpace[3] * ((v - 0.45) / 0.35).coerceIn(0.0, 1.0),
            ),
            CompositionStyle.WIDE_CONTEXT to f.scattered *
                ((scale - 0.88) / 0.08).coerceIn(0.0, 1.0),
        )
    }

    private fun describe(style: CompositionStyle, crop: ScoredCrop, features: SceneFeatures): String = when (style) {
        CompositionStyle.THIRDS -> when {
            crop.u < 0.42 -> "三分构图 · 主体靠近左侧三分线，视线前方留出空间"
            crop.u > 0.58 -> "三分构图 · 主体靠近右侧三分线，视线前方留出空间"
            crop.v < 0.42 -> "三分构图 · 主体靠近上方三分线，下方留白让画面呼吸"
            crop.v > 0.58 -> "三分构图 · 主体下沉到下方三分线，头顶留白更从容"
            else -> "三分构图 · 把视觉重点压在三分线交点上，画面更耐看"
        }
        CompositionStyle.CENTERED -> "中心构图 · 主体居中、左右呼应，用对称建立秩序感"
        CompositionStyle.DIAGONAL ->
            if (features.slashDominant) "对角线构图 · 让主体沿左下到右上的斜线展开，动势更强"
            else "对角线构图 · 让主体沿对角线展开，斜线带来方向感和动势"
        CompositionStyle.TRIANGLE -> "三角形构图 · 让三个视觉重点构成三角关系，重心更稳"
        CompositionStyle.LEADING_LINES -> "引导线构图 · 让线条从前景引向主体，视线自然深入画面"
        CompositionStyle.FRAME -> "框式构图 · 借门窗或枝叶形成画框，把视线围向主体"
        CompositionStyle.FOREGROUND_DEPTH ->
            if (features.foregroundBottomStrong) "前景层次 · 纳入下方前景与远处主体呼应，画面更有纵深"
            else "前景层次 · 保留上方前景层次，让主体从层次中显露"
        CompositionStyle.NEGATIVE_SPACE -> when {
            features.negSpace[0] >= features.negSpace[1] && features.negSpace[0] > 0.0 ->
                "留白构图 · 保留左侧大片留白，主体更突出、更有呼吸感"
            features.negSpace[3] >= features.negSpace[2] && features.negSpace[3] > 0.0 ->
                "留白构图 · 保留上方留白，主体下沉，画面更安静"
            features.negSpace[2] > 0.0 -> "留白构图 · 保留下方留白，主体上浮，留出想象空间"
            else -> "留白构图 · 保留右侧大片留白，主体更突出、更有呼吸感"
        }
        CompositionStyle.WIDE_CONTEXT -> "环境构图 · 退后一步保留环境信息，让场景本身讲故事"
        CompositionStyle.CONSERVATIVE -> "纹理与对比较少，保留完整画面更稳妥"
        CompositionStyle.GENERIC -> when {
            crop.scale >= 0.92 -> "保留更多环境与明暗结构，避免过度裁切"
            crop.u < 0.44 -> "让视觉重点靠近左侧三分区，减少周围空白"
            crop.u > 0.56 -> "让视觉重点靠近右侧三分区，减少周围空白"
            crop.v < 0.44 -> "让视觉重点靠近上方三分区，平衡画面留白"
            crop.v > 0.56 -> "让视觉重点靠近下方三分区，平衡画面留白"
            else -> "保留主要对比区域，适度收紧画面"
        }
    }

    /** Deterministic fallback. Copy varies with exposure so even empty-looking frames do not repeat one line. */
    private fun conservativeCrops(medianLuminance: Double): List<CompositionCandidate> {
        val dim = medianLuminance < 0.22
        val bright = medianLuminance > 0.85
        val reasons = when {
            dim -> listOf(
                "纹理与对比较少，保留完整画面更稳妥",
                "画面偏暗、细节不足，可靠近光源或让更多光线进入",
                "暗光下先稳住完整画面，等亮部轮廓出现再收紧",
            )
            bright -> listOf(
                "纹理与对比较少，保留完整画面更稳妥",
                "画面偏亮、对比发平，可换角度让主体与背景拉开层次",
                "高光下先保留完整画面，等阴影轮廓更明确再裁切",
            )
            else -> listOf(
                "纹理与对比较少，保留完整画面更稳妥",
                "视觉重点不明显，仅作轻微裁切参考",
                "先保留完整画面，等待更明确的主体或线条",
            )
        }
        return listOf(1f, 0.96f, 0.90f).mapIndexed { index, scale ->
            val inset = (1f - scale) / 2f
            CompositionCandidate(NormalizedBoundingBox(inset, inset, inset + scale, inset + scale),
                0.5f - index * 0.05f, reasons[index],
                supportsModelComparison = false, style = CompositionStyle.CONSERVATIVE)
        }
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

    internal class Integral(width: Int, height: Int, values: DoubleArray) {
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

        /** Average value over a normalized rectangle of the grid. */
        fun density(w: Int, h: Int, x0: Double, y0: Double, x1: Double, y1: Double): Double {
            val l = (x0 * w).roundToInt().coerceIn(0, w)
            val t = (y0 * h).roundToInt().coerceIn(0, h)
            val r = (x1 * w).roundToInt().coerceIn(l, w)
            val b = (y1 * h).roundToInt().coerceIn(t, h)
            val area = max(1, (r - l) * (b - t))
            return sum(l, t, r, b) / area
        }
    }
}

/** Scene-level composition signals, each normalized to 0..1 unless noted. */
internal class SceneFeatures private constructor(
    val symmetry: Double,
    val diagonal: Double,
    val slashDominant: Boolean,
    val leading: Double,
    val centered: Double,
    val scattered: Double,
    val frame: Double,
    val triangle: Double,
    /** Foreground band strength: index 0 = bottom band, 1 = top band. */
    val foreground: DoubleArray,
    /** Empty-band strength: index 0 = left, 1 = right, 2 = bottom, 3 = top. */
    val negSpace: DoubleArray,
) {
    val foregroundBottomStrong: Boolean get() = foreground[0] >= foreground[1]

    companion object {
        @Suppress("LongMethod", "ComplexMethod")
        fun extract(w: Int, h: Int, pixels: IntArray, signal: DoubleArray,
            energy: FastCompositionAdvisor.Integral): SceneFeatures {
            val lum = DoubleArray(w * h) { index ->
                val p = pixels[index]
                ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 255_000.0
            }
            fun l(x: Int, y: Int): Double {
                val cx = x.coerceIn(0, w - 1)
                val cy = y.coerceIn(0, h - 1)
                return lum[cy * w + cx]
            }
            val gx = DoubleArray(w * h)
            val gy = DoubleArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                gx[i] = (l(x + 1, y - 1) + 2 * l(x + 1, y) + l(x + 1, y + 1)) -
                    (l(x - 1, y - 1) + 2 * l(x - 1, y) + l(x - 1, y + 1))
                gy[i] = (l(x - 1, y + 1) + 2 * l(x, y + 1) + l(x + 1, y + 1)) -
                    (l(x - 1, y - 1) + 2 * l(x, y - 1) + l(x + 1, y - 1))
            }
            // Diagonal alignment: weighted mean of 2|gx*gy|/(gx^2+gy^2), which is 1 for a
            // pure 45-degree edge and 0 for horizontal/vertical edges. Edge magnitude is the
            // vote weight, so flat pixels do not dilute the reading.
            var diagVoteWeight = 0.0
            var diagVote = 0.0
            var orientationSign = 0.0
            for (i in gx.indices) {
                val weight = hypot(gx[i], gy[i])
                val denom = gx[i] * gx[i] + gy[i] * gy[i]
                if (denom > 1e-12) {
                    diagVote += weight * (2 * abs(gx[i] * gy[i]) / denom)
                    orientationSign += gx[i] * gy[i]
                }
                diagVoteWeight += weight
            }
            val diagAlign = if (diagVoteWeight > 1e-9) diagVote / diagVoteWeight else 0.0
            val diagonal = ((diagAlign - 0.55) / 0.25).coerceIn(0.0, 1.0)

            // Left-right mirror symmetry on luminance.
            var symmetryDiff = 0.0
            for (y in 0 until h) for (x in 0 until w / 2) {
                symmetryDiff += abs(lum[y * w + x] - lum[y * w + (w - 1 - x)])
            }
            val meanLum = lum.average()
            val symmetry = (1 - symmetryDiff / (w / 2 * h * (meanLum + 0.18))).coerceIn(0.0, 1.0)

            val totalSignal = max(1e-9, signal.sum())
            val meanDensity = totalSignal / (w * h)
            fun sigDensity(x0: Double, y0: Double, x1: Double, y1: Double) =
                energy.density(w, h, x0, y0, x1, y1)
            // 3x3 band cells.
            val cells = Array(3) { cy ->
                DoubleArray(3) { cx ->
                    sigDensity(cx / 3.0, cy / 3.0, (cx + 1) / 3.0, (cy + 1) / 3.0)
                }
            }
            val centered = ((symmetry - 0.60) / 0.25).coerceIn(0.0, 1.0) *
                (cells[1][1] / (meanDensity * 1.5)).coerceIn(0.0, 1.0)

            // Negative space: one outer band is quiet while the opposite side carries content.
            val bands = doubleArrayOf(
                sigDensity(0.0, 0.0, 0.26, 1.0),  // L
                sigDensity(0.74, 0.0, 1.0, 1.0),  // R
                sigDensity(0.0, 0.74, 1.0, 1.0),  // B
                sigDensity(0.0, 0.0, 1.0, 0.26),  // T
            )
            val opposite = doubleArrayOf(
                sigDensity(0.30, 0.0, 1.0, 1.0),
                sigDensity(0.0, 0.0, 0.70, 1.0),
                sigDensity(0.0, 0.0, 1.0, 0.70),
                sigDensity(0.0, 0.30, 1.0, 1.0),
            )
            val negSpace = DoubleArray(4) { side ->
                if (opposite[side] <= meanDensity * 1.15) return@DoubleArray 0.0
                // Empty space on every side means a small centered subject, not directional
                // negative space: suppress the reading when the other outer bands are quiet too.
                val otherSides = ((0..3).sumOf { bands[it] } - bands[side]) / 3
                val directional = (1 - otherSides / (0.8 * opposite[side])).coerceIn(0.0, 1.0)
                (1 - bands[side] / (0.5 * opposite[side])).coerceIn(0.0, 1.0) * directional
            }

            // Edge magnitude for foreground / frame readings.
            val mag = DoubleArray(w * h) { hypot(gx[it], gy[it]) }
            val magEnergy = FastCompositionAdvisor.Integral(w, h, mag)
            val globalMag = mag.average() + 1e-9
            val bottomMag = magEnergy.density(w, h, 0.0, 0.78, 1.0, 1.0)
            val topMag = magEnergy.density(w, h, 0.0, 0.0, 1.0, 0.22)
            val upperContent = sigDensity(0.0, 0.05, 1.0, 0.75)
            val lowerContent = sigDensity(0.0, 0.25, 1.0, 0.95)
            val foreground = doubleArrayOf(
                ((bottomMag / globalMag - 1.35) / 0.7).coerceIn(0.0, 1.0) *
                    (upperContent / (meanDensity * 1.1)).coerceIn(0.0, 1.0),
                ((topMag / globalMag - 1.35) / 0.7).coerceIn(0.0, 1.0) *
                    (lowerContent / (meanDensity * 1.1)).coerceIn(0.0, 1.0),
            )

            // Frame: dark or busy border around a content-bearing middle.
            val borderRegions = arrayOf(
                doubleArrayOf(0.0, 0.0, 0.12, 1.0), doubleArrayOf(0.88, 0.0, 1.0, 1.0),
                doubleArrayOf(0.0, 0.0, 1.0, 0.12), doubleArrayOf(0.0, 0.88, 1.0, 1.0),
            )
            val borderMag = borderRegions.maxOf { magEnergy.density(w, h, it[0], it[1], it[2], it[3]) }
            val centerMag = magEnergy.density(w, h, 0.26, 0.26, 0.74, 0.74)
            var borderLumSum = 0.0
            var borderLumCount = 0
            borderRegions.forEach { region ->
                val x0 = (region[0] * w).toInt(); val y0 = (region[1] * h).toInt()
                val x1 = (region[2] * w).toInt(); val y1 = (region[3] * h).toInt()
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    borderLumSum += lum[yy * w + xx]; borderLumCount++
                }
            }
            val borderLum = borderLumSum / max(1, borderLumCount)
            var centerLumSum = 0.0
            var centerLumCount = 0
            for (yy in (0.26 * h).toInt() until (0.74 * h).toInt())
                for (xx in (0.26 * w).toInt() until (0.74 * w).toInt()) {
                    centerLumSum += lum[yy * w + xx]; centerLumCount++
                }
            val centerLum = centerLumSum / max(1, centerLumCount)
            val frameByEdge = ((borderMag / (centerMag * 1.4) - 1.0) / 0.8).coerceIn(0.0, 1.0)
            // A plain dark surround around a bright object is not a frame: shading counts
            // only when the border band also carries real edge texture.
            val borderTexture = (borderMag / globalMag).coerceIn(0.0, 1.0)
            val frameByShade = ((centerLum - borderLum - 0.15) / 0.2).coerceIn(0.0, 1.0) *
                borderTexture
            val frame = max(frameByEdge, frameByShade) *
                (cells[1][1] / (meanDensity * 0.8)).coerceIn(0.0, 1.0)

            // Triangle: energy envelope is wide at the base and narrows toward the top.
            val colThreshold = meanDensity * 0.5
            var coverBottom = 0
            var coverTop = 0
            for (x in 0 until w) {
                var bottom = 0.0
                var top = 0.0
                for (y in (h / 2) until h) bottom += signal[y * w + x]
                for (y in 0 until h / 2) top += signal[y * w + x]
                if (bottom / max(1, h / 2) > colThreshold) coverBottom++
                if (top / max(1, h / 2) > colThreshold) coverTop++
            }
            val centerY = (0 until w * h).sumOf { signal[it] * (it / w + 0.5) / h } / totalSignal
            val envelope = (coverBottom / w.toDouble() - coverTop / w.toDouble() - 0.22) / 0.35
            val verticalGate = (1 - abs(centerY - 0.56) / 0.30).coerceIn(0.0, 1.0)
            val triangle = envelope.coerceIn(0.0, 1.0) * verticalGate

            // Leading lines: diagonal edge field plus energy entering from a corner.
            val cornerCell = maxOf(cells[0][0], cells[0][2], cells[2][0], cells[2][2])
            val leading = ((diagAlign - 0.60) / 0.2).coerceIn(0.0, 1.0) *
                (cornerCell / (meanDensity * 1.3)).coerceIn(0.0, 1.0)

            // Scattered content means several *distinctly* active cells; an even texture
            // that lights every cell weakly is a pattern, not multiple content regions.
            val activeCells = cells.sumOf { column -> column.count { it > meanDensity * 1.5 } }
            val scattered = ((activeCells - 3).toDouble() / 2.0).coerceIn(0.0, 1.0)

            return SceneFeatures(symmetry, diagonal, orientationSign >= 0.0, leading, centered,
                scattered, frame, triangle, foreground, negSpace)
        }
    }
}
