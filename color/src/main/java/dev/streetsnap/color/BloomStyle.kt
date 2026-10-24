package dev.streetsnap.color

/**
 * Single source of truth for the two-layer optical-diffusion look (HDF).
 *
 * Values used to live as unlabelled magic numbers inside the GLSL string and the renderer;
 * gathering them here keeps the shader readable, makes the tuning auditable (see
 * docs/hdr-film-research.md §6) and lets JVM tests pin the parameter relationships the
 * look depends on. Numbers are injected as compile-time GLSL constants, so there is no
 * extra uniform plumbing and the shader compiler optimises them exactly as before.
 */
internal object BloomStyle {

    // Highlight keying: soft knee on the BRIGHTEST channel (max(r,g,b)), so coloured lamps
    // with low luma are still extracted. Only near-clipping light enters a bloom sheet.
    const val KNEE_HDF_LO = 0.72f
    const val KNEE_HDF_HI = 0.90f
    const val KNEE_HDR_LO = 0.74f
    const val KNEE_HDR_HI = 0.92f

    // Long-edge cap (px) of each downsampled bloom sheet.
    const val CORE_SHEET_EDGE = 384
    const val WIDE_SHEET_EDGE = 192

    // Two separable Gaussian scales per layer, as a fraction of the sheet's short edge.
    // The wide halo runs on its own small sheet: widening the core blur is what earlier
    // revisions did, and it smeared lamps into a gray veil.
    val CORE_BLUR = floatArrayOf(0.008f, 0.014f)
    val WIDE_BLUR = floatArrayOf(0.026f, 0.042f)

    // Soft-diffusion look (Black Pro-Mist 1/4 style, uHdf): tinted tight core plus a faint
    // broad halo. The extracted sheet is RGBA8 and clamped, so the core near clipping is
    // recovered post-blur via the luminance gain instead of real over-clip energy.
    const val HDF_CORE_CLIP_GAIN = 0.25f   // core *= 1 + gain * luminance(core)
    const val HDF_CORE_GAIN = 1.50f
    const val HDF_WIDE_GAIN = 0.55f
    const val HDF_WIDE_SAT = 0.85f        // broad halo keeps 85% of its tint

    // Actual HDF button (legacy uHdr name): tuned a touch tighter and more restrained.
    // amount maps strength [0,1] onto a mixing gain of [HDR_AMOUNT_LO, HDR_AMOUNT_HI].
    const val HDR_AMOUNT_LO = 0.22f
    const val HDR_AMOUNT_HI = 0.68f
    const val HDR_CORE_SAT = 0.60f
    const val HDR_CORE_GAIN = 0.70f
    const val HDR_WIDE_SAT = 0.75f
    const val HDR_WIDE_GAIN = 0.45f

    // Experimental point-light layer. A third, tiny sheet is keyed from the ALREADY
    // extracted highlight sheet by local contrast (centre vs box neighbourhood): a small
    // bright lamp is locally far brighter than its neighbours and survives, while inside a
    // broad bright surface they are equal and the mask collapses, so its halo converges.
    // This is the scale-aware response to the night street-light glow request. Evidence is
    // loaded-only (AVD checkerboard proxies); the real night-light look is not verified.
    const val POINT_SHEET_EDGE = 96
    const val POINT_NEIGHBOUR = 0.05f     // box radius in point-sheet UV
    const val POINT_CONTRAST_LO = 0.15f
    const val POINT_CONTRAST_HI = 0.55f
    val POINT_BLUR = floatArrayOf(0.05f, 0.09f)
    const val POINT_SAT = 0.6f
    const val HDF_POINT_GAIN = 0.35f
    const val HDR_POINT_GAIN = 0.30f

    // Per-frame preview easing once a look is switched on; switching off snaps to zero so
    // no stale bloom texture lingers. Stills (fresh renderer) settle on the first frame.
    const val PREVIEW_EASE = 0.2f
}
