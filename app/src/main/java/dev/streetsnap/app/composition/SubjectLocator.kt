package dev.streetsnap.app.composition

import java.util.ArrayDeque
import kotlin.math.max

/**
 * The subject's location as a normalized rectangle derived from the saliency coarse grid
 * (T73). It is an image-statistic locus — "where the concentrated content mass sits" — never
 * a person/object detection. [cellCount] and [weightShare] (the block's share of all fired
 * cell weight) let callers tell one compact mass apart from scattered texture.
 */
internal data class SubjectLocus(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cellCount: Int,
    val peakStrength: Double,
    val weightShare: Double,
    /**
     * Share of the block's signal carried by colour distance to the median background vs
     * edges (same two terms as the per-pixel signal). A real subject block is a coherent
     * tonal mass (high); a hash-texture peak cell is held up by internal block edges alone
     * (low). Filled in by the advisor after [SubjectLocator.locate]; 0 until then.
     */
    val colorShare: Double = 0.0,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        /** A peak cell must carry at least this weight to seed a locus block. */
        const val SEED_WEIGHT = 0.45

        /** 4-connected neighbours join the block only at/above this weight. */
        const val JOIN_WEIGHT = 0.30

        /**
         * Weak-peak gate (the strong gate stays [0.60]/0.20 in the advisor): a real subject
         * on an ordinary busy backdrop often tops out between 0.45 and 0.60 because the
         * backdrop lifts the mean density. Pure isotropic texture tops out lower and its
         * fired cells hold only ~8% of total signal.
         */
        const val WEAK_PEAK = 0.45
        const val WEAK_ENERGY_RATIO = 0.12
    }
}

/**
 * Groups fired saliency cells into one connected block and returns its bounding rectangle.
 * The grid is the same 6-row, aspect-ratio-adapted coarse grid built in [FastCompositionAdvisor],
 * so no second statistic is introduced. Pure Kotlin.
 */
internal object SubjectLocator {

    /**
     * @param weight per-cell saliency weights, row-major ([rows] × [cols])
     * @return the block seeded by the strongest cell; null when no cell reaches [SubjectLocus.SEED_WEIGHT]
     */
    fun locate(weight: DoubleArray, cols: Int, rows: Int): SubjectLocus? {
        require(cols > 0 && rows > 0 && weight.size == cols * rows)
        val seed = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[seed] < SubjectLocus.SEED_WEIGHT) return null

        val visited = BooleanArray(weight.size)
        val queue = ArrayDeque<Int>()
        queue.add(seed)
        visited[seed] = true
        var minCol = cols
        var maxCol = -1
        var minRow = rows
        var maxRow = -1
        var blockWeight = 0.0
        var cellCount = 0
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            val gx = k % cols
            val gy = k / cols
            if (gx < minCol) minCol = gx
            if (gx > maxCol) maxCol = gx
            if (gy < minRow) minRow = gy
            if (gy > maxRow) maxRow = gy
            blockWeight += weight[k]
            cellCount++
            // 4-connected only: diagonally touching cells are separate structures.
            val neighbours = buildList {
                if (gx > 0) add(k - 1)
                if (gx + 1 < cols) add(k + 1)
                if (gy > 0) add(k - cols)
                if (gy + 1 < rows) add(k + cols)
            }
            for (n in neighbours) {
                if (!visited[n] && weight[n] >= SubjectLocus.JOIN_WEIGHT) {
                    visited[n] = true
                    queue.add(n)
                }
            }
        }
        val totalWeight = max(1e-9, weight.sum())
        return SubjectLocus(
            left = minCol / cols.toFloat(),
            top = minRow / rows.toFloat(),
            right = (maxCol + 1) / cols.toFloat(),
            bottom = (maxRow + 1) / rows.toFloat(),
            cellCount = cellCount,
            peakStrength = weight[seed],
            weightShare = blockWeight / totalWeight,
        )
    }
}
