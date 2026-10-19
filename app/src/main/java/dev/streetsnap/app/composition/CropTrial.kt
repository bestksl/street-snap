package dev.streetsnap.app.composition

import dev.streetsnap.camera.NormalizedBoundingBox
import kotlin.math.max

/**
 * T35 long-press "试取景": a display-only transform that zooms the live preview
 * so the suggested crop fills the viewfinder. Nothing here touches ImageCapture
 * — photos are always saved full-frame — nor the rule/model crop coordinates.
 *
 * The preview is scaled uniformly (never stretched): [scale] is the larger of
 * 1/width and 1/height ("cover"), so the crop centre moves to the screen centre
 * and whichever dimension is shorter fills its axis, the other side overflowing
 * under the view's clip. A suggestion that already is the full frame has
 * nothing to trial and yields null.
 */
internal data class CropTrial(
    val scale: Float,
    /** Crop centre in normalized viewfinder coordinates; the UI maps it to a TransformOrigin. */
    val centerU: Float,
    val centerV: Float,
) {
    companion object {
        fun from(crop: NormalizedBoundingBox): CropTrial? {
            val width = crop.right - crop.left
            val height = crop.bottom - crop.top
            if (width <= 0f || height <= 0f) return null
            // Same "essentially the full frame" rule as AlignmentFeedback.isFramed.
            val inset = max(max(crop.left, crop.top), max(1f - crop.right, 1f - crop.bottom))
            if (inset <= AlignmentFeedback.FRAME_TOLERANCE) return null
            return CropTrial(
                scale = max(1f / width, 1f / height),
                centerU = (crop.left + crop.right) / 2f,
                centerV = (crop.top + crop.bottom) / 2f,
            )
        }
    }
}
