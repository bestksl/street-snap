package dev.streetsnap.app.edge

/**
 * SigLIP2 NaFlex patch layout for d1's vision graph, ported from the official `vision.py`.
 *
 * The app always feeds one fixed 512x512 square contact sheet, so smart-resize and tiling
 * are skipped: 32x32 patches of 16px become 1024 patch rows of 768 floats (RGB in [-1,1]).
 * The ONNX vision graph takes the patch tensor directly; it does not do the reshape.
 */
internal object D1NaFlex {
    const val SIDE = 512
    const val PATCH = 16
    const val GRID = SIDE / PATCH // 32
    const val MAX_PATCHES = GRID * GRID // 1024
    const val PATCH_DIM = 3 * PATCH * PATCH // 768
    const val PROJECTED_PREFIX = MAX_PATCHES / 4 // 256 embeddings after the 2x2 unshuffle projector

    /**
     * @param argb packed ARGB pixels, exactly SIDE*SIDE, top-left origin.
     * @return row-major [MAX_PATCHES, PATCH_DIM] floats, channel order RGB scaled /127.5 - 1.
     */
    fun toPatchPixels(argb: IntArray): FloatArray {
        require(argb.size == SIDE * SIDE) { "d1 vision needs one $SIDE×$SIDE square frame" }
        val out = FloatArray(MAX_PATCHES * PATCH_DIM)
        for (py in 0 until GRID) for (px in 0 until GRID) {
            val patchRow = (py * GRID + px) * PATCH_DIM
            for (dy in 0 until PATCH) for (dx in 0 until PATCH) {
                val color = argb[(py * PATCH + dy) * SIDE + (px * PATCH + dx)]
                val within = (dy * PATCH + dx) * 3
                out[patchRow + within] = ((color ushr 16) and 255) / 127.5f - 1f
                out[patchRow + within + 1] = ((color ushr 8) and 255) / 127.5f - 1f
                out[patchRow + within + 2] = (color and 255) / 127.5f - 1f
            }
        }
        return out
    }
}
