package dev.streetsnap.camera

/**
 * T77: Camera2 face-detection policy, kept Android-free so selection and coordinate math are
 * unit testable. HAL face statistics are the only on-device face signal that needs no extra
 * dependency: with STATISTICS_FACE_DETECT_MODE enabled the 3A libraries follow faces
 * automatically while AF mode stays CONTINUOUS_PICTURE, and tap-focus (CameraX
 * startFocusAndMetering) keeps working untouched. The app reads faces for a focus-area
 * overlay only; it never detects or identifies people itself.
 */
internal object FaceDetectPolicy {
    // Values match CaptureRequest.STATISTICS_FACE_DETECT_MODE_* (stable ABI constants).
    const val MODE_OFF = 0
    const val MODE_SIMPLE = 1
    const val MODE_FULL = 2

    /** FULL carries eye/mouth landmarks; SIMPLE reports rectangles only; absent/empty = none. */
    fun selectMode(availableModes: IntArray?): Int? = when {
        availableModes == null -> null
        MODE_FULL in availableModes -> MODE_FULL
        MODE_SIMPLE in availableModes -> MODE_SIMPLE
        else -> null
    }

    fun modeLabel(mode: Int?): String = when (mode) {
        MODE_FULL -> "完整（含眼部点位）"
        MODE_SIMPLE -> "基础（人脸框）"
        else -> "不支持"
    }

    /**
     * Maps a face rectangle in active-array sensor pixels (landscape sensor space) into
     * normalized display-space coordinates for a rear camera preview, i.e. the frame a
     * user holding the phone in portrait sees. Returns null for a degenerate/unsupported
     * geometry rather than emitting an invalid box.
     */
    fun normalizeFace(
        left: Int, top: Int, right: Int, bottom: Int,
        activeArrayWidth: Int, activeArrayHeight: Int, sensorOrientationDegrees: Int,
    ): NormalizedBoundingBox? {
        if (activeArrayWidth <= 0 || activeArrayHeight <= 0) return null
        val x0 = left.coerceIn(0, activeArrayWidth).toFloat()
        val y0 = top.coerceIn(0, activeArrayHeight).toFloat()
        val x1 = right.coerceIn(0, activeArrayWidth).toFloat()
        val y1 = bottom.coerceIn(0, activeArrayHeight).toFloat()
        if (x1 <= x0 || y1 <= y0) return null
        val w = activeArrayWidth.toFloat()
        val h = activeArrayHeight.toFloat()
        // Four corners, then rotate the rear (non-mirrored) sensor frame into portrait space.
        val corners = listOf(
            x0 to y0, x1 to y0, x1 to y1, x0 to y1,
        ).map { (x, y) ->
            when (sensorOrientationDegrees) {
                90 -> y / h to 1f - x / w
                270 -> 1f - y / h to x / w
                180 -> 1f - x / w to 1f - y / h
                0 -> x / w to y / h
                else -> return null
            }
        }
        val nLeft = corners.minOf { it.first }.coerceIn(0f, 1f)
        val nTop = corners.minOf { it.second }.coerceIn(0f, 1f)
        val nRight = corners.maxOf { it.first }.coerceIn(0f, 1f)
        val nBottom = corners.maxOf { it.second }.coerceIn(0f, 1f)
        if (nRight <= nLeft || nBottom <= nTop) return null
        return NormalizedBoundingBox(nLeft, nTop, nRight, nBottom)
    }

    /** Many HALs report score = INVALID_SCORE; largest face area is the reliable primary. */
    fun primaryBox(boxes: List<NormalizedBoundingBox>): NormalizedBoundingBox? =
        boxes.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
}
