package dev.streetsnap.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the parameter relationships the two-layer diffusion look is tuned around. */
class BloomStyleTest {

    @Test fun kneesAreOrderedSoftRampsInsideUnitRange() {
        // Each knee is a finite lo<hi ramp, and the HDF button stays a touch tighter
        // (higher thresholds) than the softer diffusion look.
        assertTrue(BloomStyle.KNEE_HDF_LO in 0f..1f && BloomStyle.KNEE_HDF_HI in 0f..1f)
        assertTrue(BloomStyle.KNEE_HDR_LO in 0f..1f && BloomStyle.KNEE_HDR_HI in 0f..1f)
        assertTrue(BloomStyle.KNEE_HDF_LO < BloomStyle.KNEE_HDF_HI)
        assertTrue(BloomStyle.KNEE_HDR_LO < BloomStyle.KNEE_HDR_HI)
        assertTrue(BloomStyle.KNEE_HDR_LO >= BloomStyle.KNEE_HDF_LO)
        assertTrue(BloomStyle.KNEE_HDR_HI >= BloomStyle.KNEE_HDF_HI)
    }

    @Test fun wideHaloIsBroaderThanCoreAndOnASmallerSheet() {
        assertEquals(2, BloomStyle.CORE_BLUR.size)
        assertEquals(2, BloomStyle.WIDE_BLUR.size)
        // Every wide radius exceeds every core radius (the layers must not converge),
        // and the point-light halo is broader still.
        BloomStyle.WIDE_BLUR.forEach { wide ->
            BloomStyle.CORE_BLUR.forEach { core -> assertTrue(wide > core) }
        }
        BloomStyle.POINT_BLUR.forEach { point ->
            BloomStyle.WIDE_BLUR.forEach { wide -> assertTrue(point > wide) }
        }
        // Radii are positive short-edge fractions below a sane ceiling.
        (BloomStyle.CORE_BLUR + BloomStyle.WIDE_BLUR + BloomStyle.POINT_BLUR).forEach {
            assertTrue(it > 0f && it < 0.25f)
        }
        assertTrue(BloomStyle.WIDE_SHEET_EDGE < BloomStyle.CORE_SHEET_EDGE)
        assertTrue(BloomStyle.POINT_SHEET_EDGE < BloomStyle.WIDE_SHEET_EDGE)
    }

    @Test fun compositingGainsAreFiniteAndNonNegative() {
        val gains = floatArrayOf(
            BloomStyle.HDF_CORE_CLIP_GAIN, BloomStyle.HDF_CORE_GAIN, BloomStyle.HDF_WIDE_GAIN,
            BloomStyle.HDF_WIDE_SAT, BloomStyle.HDR_AMOUNT_LO, BloomStyle.HDR_AMOUNT_HI,
            BloomStyle.HDR_CORE_SAT, BloomStyle.HDR_CORE_GAIN,
            BloomStyle.HDR_WIDE_SAT, BloomStyle.HDR_WIDE_GAIN,
            BloomStyle.HDF_POINT_GAIN, BloomStyle.HDR_POINT_GAIN, BloomStyle.POINT_SAT,
        )
        gains.forEach {
            assertTrue(it.isFinite() && it >= 0f)
        }
        assertTrue(BloomStyle.HDR_AMOUNT_LO < BloomStyle.HDR_AMOUNT_HI)
        // Saturation mixes stay in the valid mix() range.
        assertTrue(BloomStyle.HDF_WIDE_SAT in 0f..1f)
        assertTrue(BloomStyle.HDR_CORE_SAT in 0f..1f)
        assertTrue(BloomStyle.HDR_WIDE_SAT in 0f..1f)
        assertTrue(BloomStyle.POINT_SAT in 0f..1f)
    }

    @Test fun pointMaskContrastRampIsOrderedAndLocal() {
        assertTrue(BloomStyle.POINT_CONTRAST_LO < BloomStyle.POINT_CONTRAST_HI)
        assertTrue(BloomStyle.POINT_CONTRAST_LO in 0f..1f && BloomStyle.POINT_CONTRAST_HI in 0f..1f)
        // Neighbourhood stays a small UV radius (a few texels on the tiny sheet).
        assertTrue(BloomStyle.POINT_NEIGHBOUR in 0.01f..0.2f)
    }

    @Test fun previewEaseIsAStableRelaxationFactor() {
        assertTrue(BloomStyle.PREVIEW_EASE in 0f..1f)
    }

    @Test fun shaderEmbedsBloomConstantsNotLiterals() {
        val fs = ColorShaders.fragment(external = true)
        // Float rendering must stay GLSL-valid decimal (no scientific notation surprises).
        assertTrue(fs.contains("const float B_KNEE_HDF_LO = 0.72;"))
        assertTrue(fs.contains("const float B_KNEE_HDR_HI = 0.92;"))
        assertTrue(fs.contains("mix(B_HDR_AMOUNT_LO, B_HDR_AMOUNT_HI, uHdr)"))
        // Point-light layer is wired (sampler + mask pass + both compositing branches).
        assertTrue(fs.contains("uniform sampler2D uBloomPoint;"))
        assertTrue(fs.contains("if (uPass == 3)"))
        assertTrue(fs.contains("texture(uBloomPoint, vUv)"))
        // The old knee literals at the extraction site must be gone.
        assertTrue(!fs.contains("? 0.72 : 0.74"))
    }
}
