package dev.streetsnap.camera

data class FrameCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class CompositionPoint(val x: Float, val y: Float)

/**
 * The bitmap is cropped to CameraX's shared ViewPort and rotated upright. Its normalized
 * coordinates therefore map directly to the rear-camera viewfinder, without a second crop.
 * This metadata retains the inverse mapping to the unrotated ImageAnalysis buffer.
 */
data class CompositionFrameGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val crop: FrameCrop,
    val rotationDegrees: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && bitmapWidth > 0 && bitmapHeight > 0)
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= sourceWidth && crop.bottom <= sourceHeight)
        require(crop.width > 0 && crop.height > 0)
        require(rotationDegrees in listOf(0, 90, 180, 270))
    }

    fun viewportToSource(point: CompositionPoint): CompositionPoint {
        val (u, v) = when (rotationDegrees) {
            90 -> point.y to 1f - point.x
            180 -> 1f - point.x to 1f - point.y
            270 -> 1f - point.y to point.x
            else -> point.x to point.y
        }
        return CompositionPoint((crop.left + u * crop.width) / sourceWidth,
            (crop.top + v * crop.height) / sourceHeight)
    }

    fun sourceToViewport(point: CompositionPoint): CompositionPoint {
        val u = (point.x * sourceWidth - crop.left) / crop.width
        val v = (point.y * sourceHeight - crop.top) / crop.height
        return when (rotationDegrees) {
            90 -> CompositionPoint(1f - v, u)
            180 -> CompositionPoint(1f - u, 1f - v)
            270 -> CompositionPoint(v, 1f - u)
            else -> CompositionPoint(u, v)
        }
    }
}
