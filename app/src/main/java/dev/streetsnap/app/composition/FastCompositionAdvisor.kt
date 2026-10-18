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
    /** Executable framing move derived from crop geometry (T33); null when no move is warranted. */
    val action: String? = null,
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
        val enriched = enrichWithStyleSeeds(selected, ranked, features)
        val subjectLooksDistant = detectDistantSubject(w, signal, selected.first())
        val styled = assignStyles(enriched, features).map { (crop, style) ->
            val move = CompositionActions.plan(
                crop.rectangle(), style, crop.u.toFloat(), crop.v.toFloat())?.sentence
            CompositionCandidate(crop.rectangle(), crop.score.toFloat(),
                describe(style, crop, features), true, style, move)
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
     * Scene-level (crop-independent) evidence for the statistic-native styles. Geometry is
     * applied afterwards in [affinities]; this is also the strength gate for style-seeded
     * candidate injection. Every value is 0 unless a measured detector fires.
     */
    private fun sceneEvidence(style: CompositionStyle, f: SceneFeatures): Double = when (style) {
        // A mirror facade is symmetry without a central mass. A strong diagonal field is
        // usually perspective rather than mirror symmetry, and a single horizontal boundary
        // makes any landscape "mirror" trivially — that reading belongs to HORIZON.
        CompositionStyle.SYMMETRY -> ((f.symmetry - 0.65) / 0.25).coerceIn(0.0, 1.0) *
            (1.0 - 0.6 * f.diagonal) * (1.0 - 0.8 * f.horizontalSplit) * f.lateralTexture
        CompositionStyle.VERTICAL -> f.verticalStrength
        CompositionStyle.HORIZON -> f.horizontalSplit
        CompositionStyle.REPETITION -> f.repetition
        CompositionStyle.CONTRAST -> f.contrast
        CompositionStyle.CONVERGENCE -> f.convergence
        // The cut-edge detector already folds in coverage span, end insets and the
        // opposite-side negative-space gate, so the raw reading is the scene evidence.
        CompositionStyle.EDGE_CUT -> f.cutEdge.max()
        else -> 0.0
    }

    /** Styles whose claim is carried by a scene detector rather than by crop geometry. */
    private val evidenceStyles = listOf(
        // Tied with another saturated reading, the actionable edge warning gets the seed.
        CompositionStyle.EDGE_CUT,
        CompositionStyle.SYMMETRY,
        CompositionStyle.VERTICAL,
        CompositionStyle.HORIZON,
        CompositionStyle.REPETITION,
        CompositionStyle.CONTRAST,
        CompositionStyle.CONVERGENCE,
    )

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
        // Frame-wide structures (symmetry / vertical / convergence) key off the crop FRAME's
        // geometric centre, not the energy centroid: one off-axis high-contrast object must
        // not decide whether the frame itself sits on the axis of symmetry.
        val centreU = crop.left + scale / 2.0
        // Symmetry keeps most of the mirrored structure and centres it; a tight crop that
        // chases one detail destroys the reading.
        val symmetryGeometry = (1 - abs(centreU - 0.5) / 0.25).coerceIn(0.0, 1.0) *
            ((scale - 0.84) / 0.08).coerceIn(0.0, 1.0)
        // The horizon claim holds only for a crop that keeps the dividing line inside the
        // frame and puts it near a third; a crop that cuts the line away scores nothing.
        val horizonLine = (f.horizonY - crop.top) / scale
        val horizonGeometry = if (horizonLine in 0.0..1.0) {
            (1 - min(abs(horizonLine - 1.0 / 3), abs(horizonLine - 2.0 / 3)) / 0.25)
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        // Converging lines read best when the vanishing point stays inside the crop near
        // the top and the structure is centred; crop energy centroid sits on the lines
        // themselves (lower half), so v is not a useful gate — top and u are.
        val convergenceGeometry = (1 - abs(centreU - 0.5) / 0.30).coerceIn(0.0, 1.0) *
            ((scale - 0.84) / 0.08).coerceIn(0.0, 1.0) *
            (1 - crop.top / 0.25).coerceIn(0.0, 1.0)
        // A bisected horizon: the dividing line sits in the middle band of BOTH the frame
        // and the crop. Tight crops that cut the line away score nothing, and a line already
        // near a third belongs to HORIZON instead.
        val splitLine = (f.horizonY - crop.top) / scale
        val horizonSplitGeometry = if (splitLine in 0.30..0.70 && f.horizonY in 0.40..0.60) {
            (1.0 - abs(f.horizonY - 0.5) / 0.10).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        // "Step back" advice is scene-level and pairs with a near-full-frame crop: no tighter
        // cut can solve content pressing against the edges.
        val fullFrameGeometry = ((scale - 0.90) / 0.10).coerceIn(0.0, 1.0)
        return mapOf(
            CompositionStyle.THIRDS to thirdsGeometry,
            CompositionStyle.CENTERED to f.centered *
                (1 - hypot(u - 0.5, v - 0.5) / 0.35).coerceIn(0.0, 1.0),
            // Diagonal and single-corner leading lines need ONE dominant slope; a balanced
            // V-shaped field belongs to CONVERGENCE instead.
            CompositionStyle.DIAGONAL to f.diagonal * f.slopePurity *
                ((scale - 0.72) / 0.14).coerceIn(0.0, 1.0) * (0.4 + 0.6 * diagonalGeometry),
            CompositionStyle.LEADING_LINES to f.leading * f.slopePurity *
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
                ((scale - 0.88) / 0.08).coerceIn(0.0, 1.0) *
                // Content pressing on the rims is the stronger "step back" reading; let
                // TOO_TIGHT own it instead of the milder "keep the context" wording.
                (1.0 - 0.75 * f.edgeCrowd),
            CompositionStyle.SYMMETRY to
                sceneEvidence(CompositionStyle.SYMMETRY, f) * symmetryGeometry,
            CompositionStyle.VERTICAL to f.verticalStrength *
                ((scale - 0.80) / 0.12).coerceIn(0.0, 1.0) *
                (1 - abs(centreU - 0.5) / 0.30).coerceIn(0.0, 1.0),
            CompositionStyle.HORIZON to f.horizontalSplit *
                ((scale - 0.72) / 0.16).coerceIn(0.0, 1.0) * horizonGeometry,
            CompositionStyle.REPETITION to f.repetition *
                ((scale - 0.84) / 0.08).coerceIn(0.0, 1.0),
            // Chiaroscuro without a clean dividing line: hard light that splits the frame
            // into tonal blocks is claimed by HORIZON/VERTICAL, contrast keeps the rest.
            CompositionStyle.CONTRAST to f.contrast *
                (1 - abs(scale - 0.88) / 0.20).coerceIn(0.0, 1.0) *
                // A frame packed edge to edge with small tonal blocks is bimodal noise, not
                // a subject straddling a tonal boundary — TOO_TIGHT owns that reading.
                (1.0 - 0.85 * f.edgeCrowd),
            CompositionStyle.CONVERGENCE to f.convergence * convergenceGeometry,
            CompositionStyle.HORIZON_SPLIT to f.horizontalSplit * horizonSplitGeometry *
                ((scale - 0.84) / 0.08).coerceIn(0.0, 1.0),
            CompositionStyle.TOO_TIGHT to f.edgeCrowd * fullFrameGeometry,
            // "Step sideways" is advice about the current framing: only a near-full-frame
            // crop may carry it (the style-seed path injects that exact crop), otherwise
            // the warning would be paired with a crop that already hides the problem.
            CompositionStyle.EDGE_CUT to f.cutEdge.max() *
                ((scale - 0.94) / 0.06).coerceIn(0.0, 1.0),
            // Minimalism is about NOT enlarging the one mass, so the claim must survive even
            // tight energy-chasing crops — no near-full-frame gate here.
            CompositionStyle.MINIMAL to f.minimal *
                ((scale - 0.55) / 0.20).coerceIn(0.0, 1.0),
        )
    }

    /**
     * Style-seeded candidate diversity. The energy-ranked top three can miss a composition
     * language that has strong scene evidence but no geometrically matching crop (a mirror
     * facade, for instance, never produces a centred crop when content sits off-axis). When
     * that happens the best-fitting crop from the wider pool replaces the third proposal, so
     * the on-device model gets three genuinely different grammars to choose between instead
     * of three near-duplicates. Prompt wording is untouched (the T07 eval showed wording
     * changes do not move the small model); the extra option itself is the intervention.
     * Evidence zero means no injection, and the replacement must still retain most energy.
     */
    private fun enrichWithStyleSeeds(
        selected: List<ScoredCrop>,
        ranked: List<ScoredCrop>,
        features: SceneFeatures,
    ): List<ScoredCrop> {
        if (selected.size < 3) return selected
        // A seed is a strong scene language the chosen crops do not already express; only
        // the strongest missing one replaces the third proposal. Checking representation
        // PER STYLE matters: a crop can express CONTRAST strongly while the same frame's
        // cut-edge warning stays completely unrepresented.
        val seed = evidenceStyles
            .associateWith { sceneEvidence(it, features) }
            .filterValues { it >= 0.60 }
            .filterKeys { style ->
                selected.maxOf { affinities(it, features)[style] ?: 0.0 } < 0.18
            }
            .maxByOrNull { it.value }
            ?: return selected
        val seedStyle = seed.key
        // A crossed-edge warning is advice about the CURRENT framing, so pair it with the
        // full-frame crop; the generic IoU (<0.80) diversity gate would reject exactly that.
        val replacement = if (seedStyle == CompositionStyle.EDGE_CUT) {
            ranked.firstOrNull { it.left == 0.0 && it.top == 0.0 && it.scale == 1.0 }
        } else {
            ranked
                .filter { crop -> selected.all { intersectionOverUnion(crop, it) < 0.80 } }
                .filter { it.score >= selected.last().score - 0.10 && it.score >= 0.45 }
                .maxByOrNull { (affinities(it, features)[seedStyle] ?: 0.0) * 0.7 + it.score * 0.3 }
        } ?: return selected
        if ((affinities(replacement, features)[seedStyle] ?: 0.0) < 0.35) return selected
        return selected.take(2) + replacement
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
        CompositionStyle.SYMMETRY -> "对称构图 · 左右几乎镜像，站到中轴线上拍，秩序感最强"
        CompositionStyle.VERTICAL -> "竖线构图 · 让竖向结构贯穿画面、保持正直，气势更足"
        CompositionStyle.HORIZON -> run {
            val line = (features.horizonY - crop.top) / crop.scale
            when {
                line !in 0.0..1.0 -> "水平分割 · 保留这条明暗分界线，让它落在三分线上"
                line < 0.42 -> "水平分割 · 分界压在上三分线，地面成为画面主角"
                line > 0.58 -> "水平分割 · 分界压在下三分线，给天空留出大片空间"
                else -> "水平分割 · 分界线卡在正中，上移要地面、下移要天空"
            }
        }
        CompositionStyle.REPETITION ->
            "节奏构图 · 重复的元素形成韵律，拍满它，或等一个异类打破节奏"
        CompositionStyle.CONTRAST ->
            if (features.darkShare >= 0.62) "明暗对比 · 光比很硬，让主体走进亮区，暗部自然沉下去"
            else "明暗对比 · 画面亮暗分明，让主体压在亮暗交界处"
        CompositionStyle.CONVERGENCE ->
            "汇聚线构图 · 两侧线条向远处收拢，把主体放在汇聚点上"
        CompositionStyle.HORIZON_SPLIT -> when {
            features.horizonY < 0.47 -> "水平腰斩 · 分界线卡在正中略偏上，压低机位让地面成为主角"
            features.horizonY > 0.53 -> "水平腰斩 · 分界线卡在正中略偏下，抬高机位给天空让出空间"
            else -> "水平腰斩 · 分界线横在正中把画面一分为二，抬高机位要天空、压低要地面"
        }
        CompositionStyle.TOO_TIGHT ->
            "取景太满 · 内容顶到画面边缘，退后半步，给主体四周留出呼吸的空间"
        CompositionStyle.EDGE_CUT -> when (features.cutEdge.withIndex().maxByOrNull { it.value }?.index) {
            0 -> "主体贴边 · 主体顶住左边缘，向右平移半步，给视线前方留出空间"
            1 -> "主体贴边 · 主体顶住右边缘，向左平移半步，给视线前方留出空间"
            2 -> "主体贴边 · 主体顶到下边缘，向上平移或退后半步，避免把脚裁掉"
            else -> "主体贴边 · 主体顶到上边缘，向下平移或退后半步，给头顶留出空间"
        }
        CompositionStyle.MINIMAL ->
            "极简留白 · 大面积素净区域本身就是语言，保持留白，别把主体拍大"
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
    /** Weighted agreement of diagonal slope sign: 1 for one-slash fields, ~0 for a V (convergence). */
    val slopePurity: Double,
    val leading: Double,
    val centered: Double,
    val scattered: Double,
    val frame: Double,
    val triangle: Double,
    /** Several strong vertical edges spread across the frame, not one object's two sides. */
    val verticalStrength: Double,
    /** A single long horizontal tonal boundary (horizon / band split). */
    val horizontalSplit: Double,
    /** Normalized y of the horizontal boundary when [horizontalSplit] fires. */
    val horizonY: Double,
    /** Equally spaced repetition along columns or rows (rhythm / pattern). */
    val repetition: Double,
    /** Bimodal tonal separation (Otsu between-class variance / total variance). */
    val contrast: Double,
    /** Share of pixels below the Otsu threshold; copy distinguishes low-key from high-key. */
    val darkShare: Double,
    /** Diagonal edges lean inward from both lower halves toward the upper centre. */
    val convergence: Double,
    /** Edge texture on BOTH flanks: symmetry needs mirrored structure, not one central mass. */
    val lateralTexture: Double,
    /** Foreground band strength: index 0 = bottom band, 1 = top band. */
    val foreground: DoubleArray,
    /** Empty-band strength: index 0 = left, 1 = right, 2 = bottom, 3 = top. */
    val negSpace: DoubleArray,
    /** Content reaches at least three outer rims while the middle stays busy (frame is overfull). */
    val edgeCrowd: Double,
    /** One edge alone is crossed by content; index 0 = left, 1 = right, 2 = bottom, 3 = top. */
    val cutEdge: DoubleArray,
    /** One small mass on a predominantly quiet field (minimalism, not a featureless frame). */
    val minimal: Double,
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
            var slopeSignVote = 0.0
            var slopeVoteWeight = 0.0
            for (i in gx.indices) {
                val weight = hypot(gx[i], gy[i])
                val denom = gx[i] * gx[i] + gy[i] * gy[i]
                if (denom > 1e-12) {
                    val diagness = 2 * abs(gx[i] * gy[i]) / denom
                    diagVote += weight * diagness
                    orientationSign += gx[i] * gy[i]
                    slopeSignVote += weight * diagness * if (gx[i] * gy[i] >= 0) 1.0 else -1.0
                    slopeVoteWeight += weight * diagness
                }
                diagVoteWeight += weight
            }
            val diagAlign = if (diagVoteWeight > 1e-9) diagVote / diagVoteWeight else 0.0
            val diagonal = ((diagAlign - 0.55) / 0.25).coerceIn(0.0, 1.0)
            val slopePurity = if (slopeVoteWeight > 1e-9) abs(slopeSignVote / slopeVoteWeight) else 0.0
            // A one-slope diagonal field projects a periodic ramp and often a bimodal
            // histogram too, but "diagonal" is the operative reading there. The suppression
            // uses the raw diagonal alignment (a shallower gate than DIAGONAL's own claim)
            // times slopePurity, so a 30-ish degree stripe field still yields to "diagonal"
            // while V-shaped fields (low purity) keep transverse rhythm such as a crossing.
            val singleSlope = ((diagAlign - 0.45) / 0.25).coerceIn(0.0, 1.0) * slopePurity

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

            // Edge magnitude shared by the vertical / horizon / frame / foreground readings.
            val mag = DoubleArray(w * h) { hypot(gx[it], gy[it]) }
            val magEnergy = FastCompositionAdvisor.Integral(w, h, mag)
            val globalMag = mag.average() + 1e-9
            val edgeThreshold = globalMag * 1.4

            // Symmetry claims mirrored STRUCTURE on both flanks. A single central bar is
            // mirror-symmetric too, but with empty flanks that reading is CENTERED instead.
            // The flank/centre ratio (not a global ratio) survives one strong off-axis object.
            val sideTexture = minOf(
                magEnergy.density(w, h, 0.0, 0.0, 0.34, 1.0),
                magEnergy.density(w, h, 0.66, 0.0, 1.0, 1.0),
            )
            val centerTexture = magEnergy.density(w, h, 0.34, 0.0, 0.66, 1.0)
            val lateralTexture = (2.0 * sideTexture / (sideTexture + centerTexture + 1e-9))
                .coerceIn(0.0, 1.0)

            // Vertical dominance needs several vertical edge COLUMNS spread across the frame.
            // One object's two flanking sides (a central pillar, a single bright bar) must not
            // read as a vertical-line composition, hence the count and span gates.
            var strongTotal = 0.0
            var verticalVote = 0.0
            var minVertColumn = w
            var maxVertColumn = 0
            var vertColumnCount = 0
            for (x in 1 until w - 1) {
                var columnStrong = 0.0
                var columnVertical = 0.0
                for (y in 1 until h - 1) {
                    val i = y * w + x
                    if (mag[i] >= edgeThreshold) {
                        columnStrong += mag[i]
                        strongTotal += mag[i]
                        if (abs(gx[i]) > 1.6 * abs(gy[i])) columnVertical += mag[i]
                    }
                }
                if (columnVertical > columnStrong * 0.6 &&
                    columnStrong > h * globalMag * 0.6) {
                    vertColumnCount++
                    minVertColumn = min(minVertColumn, x)
                    maxVertColumn = max(maxVertColumn, x)
                    verticalVote += columnVertical
                }
            }
            val verticalShare = if (strongTotal > 1e-9) verticalVote / strongTotal else 0.0
            val verticalSpan = if (vertColumnCount > 0) (maxVertColumn - minVertColumn) / w.toDouble() else 0.0
            val verticalStrength = ((verticalShare - 0.45) / 0.30).coerceIn(0.0, 1.0) *
                ((vertColumnCount - 3).toDouble() / 1.0).coerceIn(0.0, 1.0) *
                ((verticalSpan - 0.40) / 0.30).coerceIn(0.0, 1.0) *
                (1.0 - 0.6 * diagonal)

            // Longest horizontal boundary: accepted only when strong horizontal edges cover
            // most of the width and the row peak dominates the vertical peak, so a vertical
            // tonal split cannot qualify as a horizon.
            val rowHorizontal = DoubleArray(h)
            val colVerticalEnergy = DoubleArray(w)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (mag[i] >= edgeThreshold) {
                    if (abs(gy[i]) > 1.6 * abs(gx[i])) rowHorizontal[y] += mag[i]
                    if (abs(gx[i]) > 1.6 * abs(gy[i])) colVerticalEnergy[x] += mag[i]
                }
            }
            var splitRow = 1
            var rowPeak = 0.0
            for (y in 2 until h - 2) {
                if (rowHorizontal[y] > rowPeak) {
                    rowPeak = rowHorizontal[y]
                    splitRow = y
                }
            }
            // Sobel responds on BOTH rows flanking a step, so the second-strongest boundary
            // is measured outside the peak's ±2 row neighbourhood; a terraced frame then
            // shows a competing boundary while a true horizon has none.
            var secondPeak = 0.0
            for (y in 2 until h - 2) {
                if (abs(y - splitRow) >= 3 && rowHorizontal[y] > secondPeak) {
                    secondPeak = rowHorizontal[y]
                }
            }
            val colPeak = (1 until w - 1).maxOf { colVerticalEnergy[it] }
            var coveredColumns = 0
            for (x in 2 until w - 2) {
                if (abs(gy[splitRow * w + x]) > globalMag * 1.2) coveredColumns++
            }
            val coverage = coveredColumns.toDouble() / (w - 4)
            // Dominance over the SECOND strongest boundary row: a terraced multi-band frame
            // has competing rows near the peak, a true horizon has one undisputed boundary.
            val boundaryDominance = rowPeak / (secondPeak + 1e-9)
            val horizontalSplit = if (rowPeak >= colPeak * 1.15) {
                ((coverage - 0.55) / 0.30).coerceIn(0.0, 1.0) *
                    ((boundaryDominance - 2.0) / 2.0).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val horizonY = (splitRow + 0.5) / h

            // Bimodal tonal separation via Otsu's between-class variance. Contrast also needs
            // a real minority block; a clean single geometric split is reserved for HORIZON.
            val tonalHistogram = IntArray(256)
            lum.forEach { tonalHistogram[(it * 255).roundToInt().coerceIn(0, 255)]++ }
            val pixelCount = w * h
            val luminanceSum = lum.sum()
            var darkWeight = 0
            var darkSum = 0.0
            var maxVariance = 0.0
            var darkCountAtThreshold = 0
            for (t in 0 until 256) {
                darkWeight += tonalHistogram[t]
                if (darkWeight == 0) continue
                val lightWeight = pixelCount - darkWeight
                if (lightWeight == 0) break
                darkSum += tonalHistogram[t] * t / 255.0
                val meanDark = darkSum / darkWeight
                val meanLight = (luminanceSum - darkSum) / lightWeight
                val betweenVariance = darkWeight.toDouble() * lightWeight *
                    (meanDark - meanLight).pow(2.0)
                if (betweenVariance > maxVariance) {
                    maxVariance = betweenVariance
                    darkCountAtThreshold = darkWeight
                }
            }
            val totalLuminanceVariance = lum.sumOf {
                (it - luminanceSum / pixelCount).pow(2.0)
            }.coerceAtLeast(1e-12)
            val separability = (maxVariance / (pixelCount * totalLuminanceVariance)).coerceIn(0.0, 1.0)
            val darkShare = darkCountAtThreshold.toDouble() / pixelCount
            val minorityShare = min(darkShare, 1.0 - darkShare)
            val contrast = ((separability - 0.62) / 0.25).coerceIn(0.0, 1.0) *
                ((minorityShare - 0.14) / 0.10).coerceIn(0.0, 1.0) *
                (1.0 - 0.9 * horizontalSplit) * (1.0 - 0.8 * singleSlope)

            // Overfull frame (TOO_TIGHT): content fills the outer 8% rims on at least three
            // sides, the middle cell is not hollow and most grid cells are busy. The gate is
            // absolute density (edges simply do not breathe), not edges-vs-centre contrast:
            // an evenly busy texture stays "too tight", while FRAME (busy border, quiet
            // middle) and four-corner scenes fail the centre/cell-count gates instead.
            // Computed before the rhythm detector so a packed texture field can yield.
            val rimRegions = arrayOf(
                doubleArrayOf(0.0, 0.0, 0.08, 1.0), doubleArrayOf(0.92, 0.0, 1.0, 1.0),
                doubleArrayOf(0.0, 0.92, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0, 0.08),
            )
            val rimDensities = rimRegions.map { sigDensity(it[0], it[1], it[2], it[3]) }
            val crowdedRims = rimDensities.count { it >= meanDensity * 0.9 }
            val busyCells = cells.sumOf { column -> column.count { it >= meanDensity * 0.85 } }
            val edgeCrowd = ((crowdedRims - 2.5) / 0.5).coerceIn(0.0, 1.0) *
                (cells[1][1] / (meanDensity * 0.9)).coerceIn(0.0, 1.0) *
                ((busyCells - 6) / 2.0).coerceIn(0.0, 1.0) *
                // A directional field (diagonal stripes, a horizon) fills every rim by
                // geometry, not because a subject crowds the frame — that language wins.
                (1.0 - 0.85 * singleSlope.coerceAtMost(1.0)) *
                (1.0 - 0.85 * horizontalSplit)

            // Rhythm: a sharp non-zero autocorrelation lag with at least three repetitions.
            // The half-lag correlation must be negative (alternating bands/blocks); a single
            // wide pulse whose autocorrelation only decays does not count as rhythm. Diagonal
            // fields project a periodic ramp, so suppress them when diagonal alignment wins.
            fun periodicStrength(projection: DoubleArray): Double {
                val n = projection.size
                val centered = DoubleArray(n) { projection[it] - projection.average() }
                val energy = centered.sumOf { it * it }
                if (energy < 1e-9) return 0.0
                var best = 0.0
                // Rhythm means recognisable repeated ELEMENTS (railings, columns): at 96px
                // the lag must span at least an eighth of the frame. A finer-period peak
                // (a few-pixel texture or grain) reads as an over-full frame, not rhythm.
                val minLag = maxOf(3, n / 8)
                for (lag in minLag..n / 3) {
                    fun normalizedAt(offset: Int): Double {
                        var acc = 0.0
                        for (i in 0 until n - offset) acc += centered[i] * centered[i + offset]
                        return acc / (energy * (n - offset) / n)
                    }
                    // True rhythm: positive at the lag, negative half a lag later (alternation),
                    // and still positive at 2*lag (a third repetition). A single wide pulse or
                    // non-periodic terraced bands fails at least one of these.
                    val normalized = normalizedAt(lag)
                    if (normalized > best && normalizedAt(lag / 2) < 0.0 &&
                        normalizedAt(2 * lag) > normalized - 0.15
                    ) {
                        best = normalized
                    }
                }
                return best
            }
            val columnProjection = DoubleArray(w) { x ->
                var acc = 0.0
                for (y in 0 until h) acc += signal[y * w + x]
                acc
            }
            val rowProjection = DoubleArray(h) { y ->
                var acc = 0.0
                for (x in 0 until w) acc += signal[y * w + x]
                acc
            }
            val repetition = ((maxOf(periodicStrength(columnProjection),
                periodicStrength(rowProjection)) - 0.45) / 0.30).coerceIn(0.0, 1.0) *
                (1.0 - 0.85 * singleSlope) *
                // A fine texture packed against every edge (edgeCrowd saturated) is an
                // over-full frame, not a rhythm of distinct elements — railings and columns
                // leave breathing gaps, so true rhythm keeps this gate open.
                (1.0 - 0.9 * edgeCrowd)

            // Convergence: strong diagonal edges in the left half lean "/" while the right
            // half leans "\", both groups heading inward toward the upper centre. A single
            // slope field (one-slash photo) keeps one of the two shares near zero.
            var leftDiagonal = 0.0
            var leftInward = 0.0
            var rightDiagonal = 0.0
            var rightInward = 0.0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (mag[i] < edgeThreshold) continue
                val denom = gx[i] * gx[i] + gy[i] * gy[i]
                if (denom <= 1e-12 || 2 * abs(gx[i] * gy[i]) / denom < 0.7) continue
                val weight = mag[i]
                if (x < w / 2) {
                    leftDiagonal += weight
                    if (gx[i] * gy[i] > 0) leftInward += weight
                } else {
                    rightDiagonal += weight
                    if (gx[i] * gy[i] < 0) rightInward += weight
                }
            }
            val leftInwardShare = if (leftDiagonal > 1e-9) leftInward / leftDiagonal else 0.0
            val rightInwardShare = if (rightDiagonal > 1e-9) rightInward / rightDiagonal else 0.0
            val convergence = ((diagAlign - 0.50) / 0.25).coerceIn(0.0, 1.0) *
                ((min(leftInwardShare, rightInwardShare) - 0.55) / 0.30).coerceIn(0.0, 1.0)

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

            // One crossed edge (EDGE_CUT): a content mass runs out of one frame side. Band
            // density alone mistakes a horizon or a tonal split crossing the edge for this,
            // so the signal is coverage ALONG the edge: a cropped subject fills a 30..90%
            // stretch of both the outer 7% rim and the band just inside it, whereas a
            // horizontal/vertical boundary occupies either a single thin line or 100% of the
            // side (a background field). When the opposite edge is genuinely empty the
            // reading already belongs to NEGATIVE_SPACE / thirds, so yield there.
            fun sideCoverage(side: Int): Double {
                // Walk along the edge; a line position counts when both its outer-rim segment
                // and its inner-band segment carry above-average signal. The rim spans 7% of
                // the cross-edge dimension (w for L/R, h for T/B).
                val verticalEdge = side <= 1
                val across = if (verticalEdge) w else h
                val rim = max(2, (0.07 * across).toInt())
                val outer = IntArray(2)
                val inner = IntArray(2)
                val along: Int
                when (side) {
                    0 -> { outer[0] = 0; outer[1] = rim; inner[0] = rim; inner[1] = 2 * rim; along = h }
                    1 -> { outer[0] = w - rim; outer[1] = w; inner[0] = w - 2 * rim; inner[1] = w - rim; along = h }
                    2 -> { outer[0] = h - rim; outer[1] = h; inner[0] = h - 2 * rim; inner[1] = h - rim; along = w }
                    else -> { outer[0] = 0; outer[1] = rim; inner[0] = rim; inner[1] = 2 * rim; along = w }
                }
                var covered = 0
                var firstCovered = along
                var lastCovered = -1
                for (a in 0 until along) {
                    var outerSum = 0.0
                    var innerSum = 0.0
                    for (c in outer[0] until outer[1]) {
                        val idx = if (side <= 1) a * w + c else c * w + a
                        outerSum += signal[idx]
                    }
                    for (c in inner[0] until inner[1]) {
                        val idx = if (side <= 1) a * w + c else c * w + a
                        innerSum += signal[idx]
                    }
                    val segLen = (outer[1] - outer[0]).coerceAtLeast(1)
                    if (outerSum / segLen >= meanDensity * 1.1 &&
                        innerSum / segLen >= meanDensity * 1.1) {
                        covered++
                        firstCovered = minOf(firstCovered, a)
                        lastCovered = a
                    }
                }
                val coverage = covered.toDouble() / along
                if (coverage <= 0.0) return 0.0
                // A tonal split starts at a corner and runs the whole edge; a cropped subject
                // leaves breathing room at BOTH ends along the edge it crosses.
                val inset = minOf(firstCovered.toDouble(), along - 1.0 - lastCovered) / along
                val insetGate = ((inset - 0.04) / 0.06).coerceIn(0.0, 1.0)
                return coverage * insetGate
            }
            // negSpace order is L, R, B, T — the opposite edge of each cut side.
            val oppositeOf = intArrayOf(1, 0, 3, 2)
            val cutEdge = DoubleArray(4) { side ->
                val coverage = sideCoverage(side)
                // Ramp up from ~30% coverage, ramp back to zero past ~90% (full-bleed field).
                val spanGate = ((coverage - 0.30) / 0.25).coerceIn(0.0, 1.0) *
                    (1.0 - ((coverage - 0.90) / 0.10).coerceIn(0.0, 1.0))
                spanGate * (1.0 - negSpace[oppositeOf[side]])
            }

            // Minimalism: a very large share of pixels is near-silent while exactly one grid
            // cell carries a concentrated mass. Featureless frames never get here (they bail
            // out before feature extraction); a quiet field around one mass is not "no scene".
            var quietPixels = 0
            for (i in signal.indices) if (signal[i] < meanDensity * 0.25) quietPixels++
            val quietShare = ((quietPixels.toDouble() / (w * h) - 0.72) / 0.18).coerceIn(0.0, 1.0)
            val topCellShare = cells.flatMap { it.toList() }.max() / (9.0 * meanDensity)
            val minimal = if (activeCells <= 1) {
                quietShare * ((topCellShare - 0.28) / 0.22).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            // Minimalism is the stronger reading of a one-mass-on-quiet-field frame: once it
            // fires, the same quiet bands must not also sell the shot as "negative space".
            if (minimal > 0.0) {
                for (i in negSpace.indices) negSpace[i] *= (1.0 - 0.85 * minimal)
            }

            return SceneFeatures(symmetry, diagonal, orientationSign >= 0.0, slopePurity, leading,
                centered, scattered, frame, triangle, verticalStrength, horizontalSplit, horizonY,
                repetition, contrast, darkShare, convergence, lateralTexture, foreground, negSpace,
                edgeCrowd, cutEdge, minimal)
        }
    }
}
