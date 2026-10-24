package dev.streetsnap.app.composition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T73: the saliency-grid block locator. */
class SubjectLocatorTest {

    private val cols = 9
    private val rows = 6

    private fun grid(vararg cells: Pair<IntArray, Double>): DoubleArray {
        val w = DoubleArray(cols * rows)
        for ((coord, value) in cells) {
            val gx = coord[0]
            val gy = coord[1]
            w[gy * cols + gx] = value
        }
        return w
    }

    private fun cell(gx: Int, gy: Int, value: Double) = intArrayOf(gx, gy) to value

    @Test
    fun nullWhenNoCellReachesSeedWeight() {
        // Uniform low firing: texture-like, nothing seeds a block.
        val w = DoubleArray(cols * rows) { 0.20 }
        assertNull(SubjectLocator.locate(w, cols, rows))
    }

    @Test
    fun compactBlockBoundsMatchItsCells() {
        // 2x2 block around the centre, each cell 0.6 wide × 0.5 tall.
        val w = grid(
            cell(4, 2, 0.80), cell(5, 2, 0.70),
            cell(4, 3, 0.65), cell(5, 3, 0.55),
        )
        val locus = assertNotNullOrThrow(SubjectLocator.locate(w, cols, rows))
        assertEquals(4, locus.cellCount)
        assertEquals(4f / 9f, locus.left, 1e-6f)
        assertEquals(2f / 6f, locus.top, 1e-6f)
        assertEquals(6f / 9f, locus.right, 1e-6f)
        assertEquals(4f / 6f, locus.bottom, 1e-6f)
        // Cells 4-5 in a 9-col grid span 4/9..6/9, centre 5/9 (not exactly canvas centre).
        assertEquals(5f / 9f, locus.centerX, 1e-6f)
        assertEquals(0.5f, locus.centerY, 1e-6f)
        assertEquals(0.80, locus.peakStrength, 1e-9)
        // Block owns essentially all fired weight.
        assertTrue(locus.weightShare > 0.95)
    }

    @Test
    fun floodFillsOnly4ConnectedNeighboursAboveJoinWeight() {
        val w = grid(
            cell(4, 2, 0.95), cell(5, 2, 0.35), cell(6, 2, 0.35),
            cell(4, 3, 0.31),
            cell(3, 1, 0.90), // diagonal-only from the seed: separate structure
            cell(2, 4, 0.40), // isolated: never reached
        )
        val locus = assertNotNullOrThrow(SubjectLocator.locate(w, cols, rows))
        // Seed (4,2) joins right ×2 and below; the diagonal 0.90 cell stays out.
        assertEquals(4, locus.cellCount)
        assertEquals(4f / 9f, locus.left, 1e-6f)
        assertEquals(7f / 9f, locus.right, 1e-6f)
    }

    @Test
    fun weakNeighboursBelowJoinWeightAreExcluded() {
        val w = grid(
            cell(4, 2, 0.70),
            cell(5, 2, 0.29),
            cell(4, 3, 0.10),
        )
        val locus = assertNotNullOrThrow(SubjectLocator.locate(w, cols, rows))
        assertEquals(1, locus.cellCount)
        assertEquals(4f / 9f, locus.left, 1e-6f)
        assertEquals(5f / 9f, locus.right, 1e-6f)
    }

    @Test
    fun blockSeedIsTheStrongestCellNotTheFirstOne() {
        val w = grid(
            cell(1, 1, 0.50),
            cell(7, 4, 0.95), cell(8, 4, 0.60),
        )
        val locus = assertNotNullOrThrow(SubjectLocator.locate(w, cols, rows))
        assertTrue(locus.left >= 0.5f)
        assertEquals(0.95, locus.peakStrength, 1e-9)
        assertEquals(2, locus.cellCount)
    }

    @Test
    fun scatteredFiringKeepsWeightShareLow() {
        // One seed plus faint cells all over: the connected block is small and holds
        // only a fraction of total fired weight — the signal used to reject texture.
        val scattered = DoubleArray(cols * rows) { 0.05 }
        scattered[2 * cols + 4] = 0.50
        scattered[2 * cols + 5] = 0.40
        val locus = assertNotNullOrThrow(SubjectLocator.locate(scattered, cols, rows))
        assertEquals(2, locus.cellCount)
        assertTrue("share was ${locus.weightShare}", locus.weightShare < 0.60)
    }

    private fun <T : Any> assertNotNullOrThrow(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
