package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import kotlin.math.abs

/** Where the floating viewfinder arrow should point. Eight compass moves plus a dolly-in. */
internal enum class GuideDirection {
    UP, DOWN, LEFT, RIGHT, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT, FORWARD
}

/** A single, immediately understandable nudge shown as the animated viewfinder arrow. */
internal data class MoveGuide(val direction: GuideDirection)

/**
 * Pure geometry for the viewfinder movement arrow (2026-10-20 batch). The arrow must
 * agree with [CompositionActions]' sentence, so it reuses the same dead zones and the
 * same sign convention: a suggested crop to the right of the frame means the camera
 * moves right. When any pan/tilt offset is present it wins (one arrow, not a cluster);
 * only a centred-but-distant crop asks the user to move in. Built-in problem styles
 * (edge cut / too tight) bind the full frame and therefore yield no arrow here, just
 * as they abstain in the action planner.
 */
internal object MoveGuides {
    fun fromCrop(crop: NormalizedBoundingBox): MoveGuide? {
        val centerU = (crop.left + crop.right) / 2.0
        val centerV = (crop.top + crop.bottom) / 2.0
        val du = centerU - 0.5
        val dv = centerV - 0.5
        val horizontal = when {
            abs(du) < CompositionActions.PAN_MIN -> null
            du > 0.0 -> GuideDirection.RIGHT
            else -> GuideDirection.LEFT
        }
        val vertical = when {
            abs(dv) < CompositionActions.TILT_MIN -> null
            dv > 0.0 -> GuideDirection.DOWN
            else -> GuideDirection.UP
        }
        if (horizontal != null || vertical != null) {
            val direction = when {
                horizontal == GuideDirection.LEFT && vertical == GuideDirection.UP -> GuideDirection.UP_LEFT
                horizontal == GuideDirection.LEFT && vertical == GuideDirection.DOWN -> GuideDirection.DOWN_LEFT
                horizontal == GuideDirection.RIGHT && vertical == GuideDirection.UP -> GuideDirection.UP_RIGHT
                horizontal == GuideDirection.RIGHT && vertical == GuideDirection.DOWN -> GuideDirection.DOWN_RIGHT
                horizontal != null -> horizontal // pan only: LEFT or RIGHT
                else -> vertical!! // pan dead zone but tilt active: UP or DOWN
            }
            return MoveGuide(direction)
        }
        // Crops never exceed the frame, so the only scale move ever indicated is forward.
        val forwardGap = 1.0 - (crop.right.toDouble() - crop.left.toDouble())
        return if (forwardGap >= CompositionActions.DOLLY_MIN) MoveGuide(GuideDirection.FORWARD) else null
    }
}

/**
 * Screen rotation for the drawn chevron (0° points right), clockwise positive.
 * Kept next to the geometry so all eight directions are unit-testable; FORWARD is
 * rendered as a separate two-chevron symbol and returns 0° unused.
 */
internal val GuideDirection.arrowAngleDeg: Float
    get() = when (this) {
        GuideDirection.RIGHT -> 0f
        GuideDirection.DOWN_RIGHT -> 45f
        GuideDirection.DOWN -> 90f
        GuideDirection.DOWN_LEFT -> 135f
        GuideDirection.LEFT -> 180f
        GuideDirection.UP_LEFT -> -135f
        GuideDirection.UP -> -90f
        GuideDirection.UP_RIGHT -> -45f
        GuideDirection.FORWARD -> 0f
    }

/** Chinese accessibility/UX label, worded to match [CompositionActions]' move sentence. */
internal val GuideDirection.arrowLabel: String
    get() = when (this) {
        GuideDirection.LEFT -> "向左平移"
        GuideDirection.RIGHT -> "向右平移"
        GuideDirection.UP -> "机位抬高"
        GuideDirection.DOWN -> "机位降低"
        GuideDirection.UP_LEFT -> "向左上方移动"
        GuideDirection.UP_RIGHT -> "向右上方移动"
        GuideDirection.DOWN_LEFT -> "向左下方移动"
        GuideDirection.DOWN_RIGHT -> "向右下方移动"
        GuideDirection.FORWARD -> "靠近被摄主体"
    }
