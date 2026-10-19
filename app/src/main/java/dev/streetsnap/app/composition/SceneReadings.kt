package dev.streetsnap.app.composition

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** White-balance tendency of the light: red-over-blue (warm) or blue-over-red (cool). */
internal enum class ColorWarmth { WARM, NEUTRAL, COOL }

/** Coarse time-of-day bucket. [UNKNOWN] is the deliberate abstention for ambiguous indoor light. */
internal enum class DayPhase { DAY, DUSK, NIGHT, UNKNOWN }

/**
 * Model-free colour and light readings for one analysis frame. These are coarse image
 * statistics for rule-based suggestions (look recommendations, exposure hints), never
 * measurements of the saved photo and never subject detection. Every value is deterministic
 * given the pixels; thresholds live in [SceneReadings] so the mapping stays unit-testable.
 */
internal data class SceneReading(
    val sampleWidth: Int,
    val sampleHeight: Int,
    /** Rec. 601 luma averaged over the frame, 0..1. */
    val meanLuma: Double,
    /** meanRed - meanBlue in 0..1 units; positive is warm, negative cool (stable in dark scenes). */
    val warmthIndex: Double,
    val warmth: ColorWarmth,
    /** Mean per-pixel chroma (max-min)/max channel, 0..1: the RGB channel spread the brief asks for. */
    val saturation: Double,
    /** Population standard deviation of luma, 0..~0.5 (0.5 = a pure black/white two-tone frame). */
    val contrast: Double,
    /** Fraction of pixels with any channel at/above the highlight cap (channel clipping). */
    val highlightClipping: Double,
    /** Fraction of pixels whose brightest channel is at/below the shadow cap (near-black). */
    val shadowClipping: Double,
    /** Blue-sky-like pixels as a fraction of the upper half; 0 when the frame has no upper half. */
    val skyRatioUpper: Double,
    /** Green-foliage-like pixels as a fraction of the upper half; mutually exclusive with sky. */
    val foliageRatioUpper: Double,
    val dayPhase: DayPhase,
)

/**
 * Coarse scene statistics from the same low-resolution RGB frame the composition advisor
 * consumes, so no extra camera stream is attached. A single O(n) pass over a <=96px point
 * sample keeps the cost negligible next to [FastCompositionAdvisor].
 */
internal object SceneReadings {
    /** |meanR - meanB| above this reads as warm/cool; between is neutral. */
    const val WARMTH_THRESHOLD = 0.06
    /** Mean luma below this at any warmth reads as night. */
    const val NIGHT_LUMA = 0.20
    /** Mean luma above this reads as day; the band between is dusk/unknown territory. */
    const val DAY_LUMA = 0.55
    /** Dusk needs a stronger warm cast than plain warm white balance, plus low-mid brightness. */
    const val DUSK_WARMTH = 0.10
    const val HIGHLIGHT_CHANNEL = 250
    const val SHADOW_CHANNEL = 10
    // Sky: blue clearly above red, green not above blue, reasonably bright (rejects white cloud).
    const val SKY_BLUE_OVER_RED = 18
    const val SKY_BLUE_MIN = 85
    const val SKY_GREEN_OVER_BLUE_MAX = 15
    // Foliage: green leads both other channels by a visible margin, mid-tone (rejects neon/black).
    const val FOLIAGE_GREEN_OVER_RED = 12
    const val FOLIAGE_GREEN_OVER_BLUE = 6
    const val FOLIAGE_GREEN_MIN = 55
    const val FOLIAGE_GREEN_MAX = 205

    fun read(sourceWidth: Int, sourceHeight: Int, argb: IntArray): SceneReading {
        require(sourceWidth > 0 && sourceHeight > 0 &&
            sourceWidth.toLong() * sourceHeight == argb.size.toLong())
        // Same centre-point down-sampling as FastCompositionAdvisor so both consumers agree.
        val factor = minOf(1.0, 96.0 / maxOf(sourceWidth, sourceHeight))
        val w = maxOf(1, (sourceWidth * factor).roundToInt())
        val h = maxOf(1, (sourceHeight * factor).roundToInt())

        var sumRed = 0L
        var sumGreen = 0L
        var sumBlue = 0L
        var sumLuma = 0.0
        var sumLumaSquared = 0.0
        var sumChroma = 0.0
        var highlights = 0
        var shadows = 0
        var upperCount = 0
        var sky = 0
        var foliage = 0

        for (y in 0 until h) {
            val sy = minOf(sourceHeight - 1, ((y + 0.5) * sourceHeight / h).toInt())
            val upper = y * 2 < h
            for (x in 0 until w) {
                val sx = minOf(sourceWidth - 1, ((x + 0.5) * sourceWidth / w).toInt())
                val packed = argb[sy * sourceWidth + sx]
                val r = packed shr 16 and 255
                val g = packed shr 8 and 255
                val b = packed and 255
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val luma = (299 * r + 587 * g + 114 * b) / 255_000.0
                sumRed += r
                sumGreen += g
                sumBlue += b
                sumLuma += luma
                sumLumaSquared += luma * luma
                if (max > 0) sumChroma += (max - min).toDouble() / max
                if (max >= HIGHLIGHT_CHANNEL) highlights++
                if (max <= SHADOW_CHANNEL) shadows++
                if (upper) {
                    upperCount++
                    when {
                        b - r >= SKY_BLUE_OVER_RED && b >= SKY_BLUE_MIN && g - b <= SKY_GREEN_OVER_BLUE_MAX -> sky++
                        g - r >= FOLIAGE_GREEN_OVER_RED && g - b >= FOLIAGE_GREEN_OVER_BLUE &&
                            g in FOLIAGE_GREEN_MIN..FOLIAGE_GREEN_MAX -> foliage++
                    }
                }
            }
        }

        val n = w * h
        val meanRed = sumRed.toDouble() / n / 255.0
        val meanGreen = sumGreen.toDouble() / n / 255.0
        val meanBlue = sumBlue.toDouble() / n / 255.0
        val meanLuma = sumLuma / n
        val warmthIndex = meanRed - meanBlue
        val contrast = sqrt(maxOf(0.0, sumLumaSquared / n - meanLuma * meanLuma))
        val dayPhase = when {
            meanLuma < NIGHT_LUMA -> DayPhase.NIGHT
            warmthIndex >= DUSK_WARMTH && meanLuma <= DAY_LUMA -> DayPhase.DUSK
            meanLuma > DAY_LUMA -> DayPhase.DAY
            else -> DayPhase.UNKNOWN
        }
        return SceneReading(
            sampleWidth = w,
            sampleHeight = h,
            meanLuma = meanLuma,
            warmthIndex = warmthIndex,
            warmth = when {
                warmthIndex > WARMTH_THRESHOLD -> ColorWarmth.WARM
                warmthIndex < -WARMTH_THRESHOLD -> ColorWarmth.COOL
                else -> ColorWarmth.NEUTRAL
            },
            saturation = sumChroma / n,
            contrast = contrast,
            highlightClipping = highlights.toDouble() / n,
            shadowClipping = shadows.toDouble() / n,
            skyRatioUpper = if (upperCount > 0) sky.toDouble() / upperCount else 0.0,
            foliageRatioUpper = if (upperCount > 0) foliage.toDouble() / upperCount else 0.0,
            dayPhase = dayPhase,
        )
    }
}
