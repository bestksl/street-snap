package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import kotlin.math.abs

/** One actionable axis of a framing move. */
internal enum class MoveDirection { LEFT, RIGHT, UP, DOWN, FORWARD }

/** A single move component with a 1..3 magnitude (一点 / 半步 / 一步). */
internal data class MovePart(val direction: MoveDirection, val step: Int) {
    init {
        require(step in 1..3)
    }
}

/**
 * The geometric difference between the suggested crop and the current full-frame
 * framing, turned into an executable, 2-seconds-doable move. A crop that keeps the
 * full frame centred and near-full-scale yields no move at all.
 */
internal data class CompositionMove(
    val parts: List<MovePart>,
    /** Optional anchor phrase, e.g. "让主体落在右三分线"; null when the style carries its own goal. */
    val targetPhrase: String?,
) {
    init {
        require(parts.isNotEmpty())
    }

    /** The single Chinese action sentence shown above the style terminology. */
    val sentence: String
        get() = parts.joinToString("，") { it.phrase() } +
            (targetPhrase?.let { "，$it" } ?: "")

    private fun MovePart.phrase(): String = when (direction) {
        MoveDirection.LEFT -> "向左平移$magnitude"
        MoveDirection.RIGHT -> "向右平移$magnitude"
        MoveDirection.UP -> "机位抬高$magnitude"
        MoveDirection.DOWN -> "机位降低$magnitude"
        MoveDirection.FORWARD -> if (step >= 3) "再靠近$magnitude" else "靠近$magnitude"
    }

    private val MovePart.magnitude: String
        get() = when (step) {
            1 -> "一点"
            2 -> "半步"
            else -> "一步"
        }
}

/**
 * Pure geometry-to-action planner (T28 §2.2 standard 1/2: verb + direction +
 * magnitude, derived from centre/scale differences only). No scene interpretation
 * happens here: styles whose terminology copy is already an explicit move
 * (edge cut, too-tight, bisected horizon) abstain so the card never says it twice.
 */
internal object CompositionActions {
    // Dead zones and three magnitude bands, in normalized-frame units. A 6% centre
    // offset or an 8% scale difference is the smallest move worth asking for.
    const val PAN_MIN = 0.06
    const val PAN_HALF = 0.12
    const val PAN_FULL = 0.20
    const val TILT_MIN = 0.06
    const val TILT_HALF = 0.12
    const val TILT_FULL = 0.20
    const val DOLLY_MIN = 0.07
    const val DOLLY_HALF = 0.15
    const val DOLLY_FULL = 0.25
    const val THIRD_TOLERANCE = 0.15

    fun plan(
        crop: NormalizedBoundingBox,
        style: CompositionStyle,
        subjectU: Float = 0.5f,
        subjectV: Float = 0.5f,
    ): CompositionMove? {
        // These styles already speak in explicit camera moves ("向右平移半步",
        // "退后半步", "抬高机位给天空让出空间") — reuse that sentence directly.
        // CONSERVATIVE means "keep the whole frame", which contradicts a move.
        if (style in ACTION_BUILT_IN) return null

        val centerU = (crop.left + crop.right) / 2.0
        val centerV = (crop.top + crop.bottom) / 2.0
        val scale = (crop.right - crop.left).toDouble()
        val parts = buildList {
            step((centerU - 0.5), PAN_MIN, PAN_HALF, PAN_FULL,
                MoveDirection.LEFT, MoveDirection.RIGHT)?.let(::add)
            step((centerV - 0.5), TILT_MIN, TILT_HALF, TILT_FULL,
                MoveDirection.UP, MoveDirection.DOWN)?.let(::add)
            // Crops never exceed the frame, so the only scale move ever needed is forward.
            step(1.0 - scale, DOLLY_MIN, DOLLY_HALF, DOLLY_FULL,
                MoveDirection.FORWARD, MoveDirection.FORWARD)?.let(::add)
        }
        if (parts.isEmpty()) return null
        return CompositionMove(parts, targetPhrase(style, subjectU.toDouble(), subjectV.toDouble()))
    }

    /** Positive offset moves toward [positive], negative toward [negative]. */
    private fun step(
        offset: Double,
        min: Double,
        half: Double,
        full: Double,
        negative: MoveDirection,
        positive: MoveDirection,
    ): MovePart? {
        val magnitude = abs(offset)
        if (magnitude < min) return null
        val level = when {
            magnitude < half -> 1
            magnitude < full -> 2
            else -> 3
        }
        return MovePart(if (offset > 0) positive else negative, level)
    }

    private fun targetPhrase(style: CompositionStyle, u: Double, v: Double): String? = when (style) {
        CompositionStyle.THIRDS -> when {
            abs(u - 1.0 / 3.0) <= THIRD_TOLERANCE -> "让主体落在左三分线"
            abs(u - 2.0 / 3.0) <= THIRD_TOLERANCE -> "让主体落在右三分线"
            abs(v - 1.0 / 3.0) <= THIRD_TOLERANCE -> "让主体落在上三分线"
            abs(v - 2.0 / 3.0) <= THIRD_TOLERANCE -> "让主体落在下三分线"
            else -> null
        }
        CompositionStyle.CENTERED -> "让主体居中"
        else -> null
    }

    /** Styles whose copy is already an explicit move (or "keep the whole frame"): the planner abstains and alignment praise (T34) never fires. */
    internal val ACTION_BUILT_IN = setOf(
        CompositionStyle.EDGE_CUT,
        CompositionStyle.TOO_TIGHT,
        CompositionStyle.HORIZON_SPLIT,
        CompositionStyle.CONSERVATIVE,
    )
}
