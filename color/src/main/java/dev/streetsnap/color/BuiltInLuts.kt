package dev.streetsnap.color

import kotlin.math.pow

/**
 * Independently authored style studies, generated here so every adjustment is inspectable.
 * These are not extracted manufacturer LUTs or claims of matching a particular camera.
 */
internal object BuiltInLuts {
    val original by lazy { CubeLut.identity() }
    /** Slide-like contrast: deep shadows, vivid color and gently warm highlights. */
    val positive by lazy {
        CubeLut.generate("正片") { r, g, b ->
            val l = luma(r, g, b)
            val tone = l + .25f * (l - .5f) * 4f * l * (1f - l)
            val shadow = (1f - l).pow(2)
            val highlight = l * l
            floatArrayOf(
                tone + (r - l) * 1.16f + .018f * highlight - .006f * shadow,
                tone + (g - l) * 1.12f + .004f * highlight,
                tone + (b - l) * 1.14f - .020f * highlight + .012f * shadow,
            )
        }
    }
    /** Negative-like print: lifted toe, rolled highlights, warm skin and cool shadows. */
    val negative by lazy {
        CubeLut.generate("负片") { r, g, b ->
            val l = luma(r, g, b)
            val tone = .035f + .93f * (l + .055f * 4f * l * (1f - l))
            val shadow = (1f - l).pow(2)
            val highlight = l * l
            floatArrayOf(
                tone + (r - l) * .89f + .021f * highlight - .008f * shadow,
                tone + (g - l) * .88f + .012f * shadow,
                tone + (b - l) * .86f - .025f * highlight + .016f * shadow,
            )
        }
    }
    /** Lift middle tones and selectively soften blue; keep neutral endpoints and warm chroma. */
    val powderBlue by lazy {
        CubeLut.generate("粉末蓝") { r, g, b ->
            val l = luma(r, g, b)
            // Leave a neutral zone wider than one 17³ cell so interpolation cannot tint grays.
            val blue = (maxOf(0f, b - r - .065f) * maxOf(0f, b - g - .065f) * 5.2f).coerceIn(0f, 1f)
            val middle = 4f * l * (1f - l)
            val tone = l + .08f * middle
            val saturation = 1f - .18f * blue
            val freshBlue = blue * middle
            floatArrayOf(
                tone + (r - l) * saturation - .018f * freshBlue,
                tone + (g - l) * saturation + .008f * freshBlue,
                tone + (b - l) * saturation - .026f * freshBlue,
            )
        }
    }
    val mono by lazy {
        CubeLut.generate("黑白") { r, g, b ->
            val luminance = (r * .2126f + g * .7152f + b * .0722f)
            val tone = ((luminance - .5f) * 1.18f + .5f).coerceIn(0f, 1f)
            floatArrayOf(tone, tone, tone)
        }
    }
    val sunny by lazy {
        CubeLut.generate("晴街") { r, g, b ->
            val l = r * .2126f + g * .7152f + b * .0722f
            val highlight = l * l
            floatArrayOf(
                (r - .5f) * 1.08f + .5f + .025f * highlight,
                (g - .5f) * 1.06f + .5f + .008f * highlight,
                (b - .5f) * 1.04f + .5f - .025f * highlight + .012f * (1f - l),
            )
        }
    }

    private fun luma(r: Float, g: Float, b: Float): Float = r * .2126f + g * .7152f + b * .0722f
}
