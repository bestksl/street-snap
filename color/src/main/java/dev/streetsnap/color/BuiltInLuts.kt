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
    /** Airy midtones, cyan-blue shadows and softer blues, with warm-subject protection. */
    val powderBlue by lazy {
        CubeLut.generate("粉末蓝") { r, g, b ->
            val l = luma(r, g, b)
            // Respond to navy/gray-blue as well as saturated sky. The older blue-only mask
            // missed the cool shadows visible in Sigma's official Standard/Powder Blue pairs.
            val blue = smooth(.015f, .28f, b - r) * smooth(-.03f, .18f, b - g)
            val warm = smooth(.015f, .16f, r - b) * smooth(-.01f, .12f, r - g)
            val middle = 4f * l * (1f - l)
            val coolShadow = (1f - smooth(.25f, .8f, l)) * middle * (1f - .9f * warm)
            val tone = l + .10f * middle
            val saturation = .93f - .19f * blue
            floatArrayOf(
                tone + (r - l) * saturation - .026f * coolShadow - .020f * blue * middle + .006f * warm * middle,
                tone + (g - l) * saturation + .010f * coolShadow + .025f * blue * middle,
                tone + (b - l) * saturation + .024f * coolShadow + .006f * blue * middle - .005f * warm * middle,
            )
        }
    }
    val mono by lazy {
        CubeLut.generate("高反差黑白") { r, g, b ->
            val luminance = (r * .2126f + g * .7152f + b * .0722f)
            val tone = ((luminance - .5f) * 1.42f + .5f).coerceIn(0f, 1f)
            floatArrayOf(tone, tone, tone)
        }
    }
    /** Muted colors and firmer shadows, authored from the public Classic Chrome description. */
    val classicChrome by lazy {
        CubeLut.generate("CC · 经典正片") { r, g, b ->
            val l = luma(r, g, b)
            val middle = 4f * l * (1f - l)
            val shadow = (1f - l).pow(2) * middle
            val tone = contrast(l, .18f)
            floatArrayOf(
                tone + (r - l) * .76f - .012f * shadow,
                tone + (g - l) * .76f + .008f * shadow,
                tone + (b - l) * .72f - .014f * l * middle,
            )
        }
    }
    /** Firm print contrast, split warm/cool tones and deeper green foliage. */
    val classicNegative by lazy {
        CubeLut.generate("NC · 经典负片") { r, g, b ->
            val l = luma(r, g, b)
            val middle = 4f * l * (1f - l)
            val green = smooth(.01f, .25f, g - r) * smooth(.0f, .18f, g - b)
            val tone = .012f + .976f * contrast(l, .29f) - .035f * green * middle
            val split = (l - .45f) * middle
            floatArrayOf(
                tone + (r - l) * .98f + .045f * split,
                tone + (g - l) * .94f - .018f * split,
                tone + (b - l) * .90f - .040f * split,
            )
        }
    }
    /** Amber highlights and open shadows, without tinting the exact black/white endpoints. */
    val nostalgicNegative by lazy {
        CubeLut.generate("NA · 怀旧负片") { r, g, b ->
            val l = luma(r, g, b)
            val middle = 4f * l * (1f - l)
            val amber = smooth(.2f, .85f, l) * middle
            val tone = l + .035f * middle
            floatArrayOf(
                tone + (r - l) * .94f + .023f * amber,
                tone + (g - l) * .92f + .007f * amber,
                tone + (b - l) * .88f - .027f * amber,
            )
        }
    }
    val acros by lazy {
        CubeLut.generate("ACROS · 黑白") { r, g, b ->
            // Broad toe and shoulder retain more tonal separation than hard high-contrast mono.
            val tone = contrast(luma(r, g, b), .21f)
            floatArrayOf(tone, tone, tone)
        }
    }
    val lumixNatural by lazy {
        CubeLut.generate("自然") { r, g, b ->
            val l = luma(r, g, b)
            val tone = l + .018f * 4f * l * (1f - l)
            floatArrayOf(tone + (r - l) * .94f, tone + (g - l) * .94f, tone + (b - l) * .94f)
        }
    }
    val lumixMono by lazy {
        CubeLut.generate("L.单色") { r, g, b ->
            val l = r * .25f + g * .66f + b * .09f
            val tone = .015f + .97f * contrast(l, .12f)
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

    private fun contrast(l: Float, amount: Float): Float = l + amount * (l - .5f) * 4f * l * (1f - l)

    private fun smooth(low: Float, high: Float, value: Float): Float {
        val x = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
