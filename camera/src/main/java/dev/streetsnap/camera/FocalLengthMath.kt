package dev.streetsnap.camera

import kotlin.math.hypot

/** Pure calculations kept separate from Android capability queries. */
object FocalLengthMath {
    private val fullFrameDiagonalMm = hypot(36.0, 24.0)

    /** Estimate from the active sensor area, centre-cropped to the requested output aspect ratio. */
    fun equivalentMm(
        focalLengthMm: Double,
        activeSensorWidthMm: Double,
        activeSensorHeightMm: Double,
        outputAspectRatio: Double = 4.0 / 3.0,
    ): Double? {
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0.0 ||
            !activeSensorWidthMm.isFinite() || activeSensorWidthMm <= 0.0 ||
            !activeSensorHeightMm.isFinite() || activeSensorHeightMm <= 0.0 ||
            !outputAspectRatio.isFinite() || outputAspectRatio <= 0.0
        ) return null

        val longSide = maxOf(activeSensorWidthMm, activeSensorHeightMm)
        val shortSide = minOf(activeSensorWidthMm, activeSensorHeightMm)
        val aspectRatio = maxOf(outputAspectRatio, 1.0 / outputAspectRatio)
        val croppedWidth: Double
        val croppedHeight: Double
        if (longSide / shortSide > aspectRatio) {
            croppedHeight = shortSide
            croppedWidth = shortSide * aspectRatio
        } else {
            croppedWidth = longSide
            croppedHeight = longSide / aspectRatio
        }
        return focalLengthMm * fullFrameDiagonalMm / hypot(croppedWidth, croppedHeight)
    }

    /** Digital cropping cannot make a camera wider. Unknown metadata exposes only native view. */
    fun zoomRatio(equivalentMm: Int, nativeEquivalentMm: Double?, maxZoomRatio: Float): Float? {
        val native = nativeEquivalentMm ?: return null
        if (!native.isFinite() || native <= 0.0 || !maxZoomRatio.isFinite() || maxZoomRatio < 1f) {
            return null
        }
        val ratio = equivalentMm / native
        if (ratio < 1.0 || ratio > maxZoomRatio.toDouble()) return null
        return ratio.toFloat()
    }

    fun availableOptions(nativeEquivalentMm: Double?, maxZoomRatio: Float): List<FocalLength> {
        val crops = FocalLength.entries.filter { option ->
            option.equivalentMm?.let { zoomRatio(it, nativeEquivalentMm, maxZoomRatio) != null } == true
        }
        return if (crops.isEmpty()) listOf(FocalLength.NATIVE) else crops
    }
}
