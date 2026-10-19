package dev.streetsnap.color

/** One grading implementation for OES preview and sRGB still textures, including bloom extraction. */
internal object ColorShaders {
    const val vertex = """#version 300 es
        precision highp float;
        out vec2 vUv;
        uniform vec4 uTile;
        void main() {
            vec2 uv = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
            vUv = uTile.xy + uv * uTile.zw;
            gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
        }
    """

    fun fragment(external: Boolean): String = """#version 300 es
        ${if (external) "#extension GL_OES_EGL_image_external_essl3 : require" else ""}
        precision highp float;
        precision highp sampler3D;
        uniform highp ${if (external) "samplerExternalOES" else "sampler2D"} uImage;
        uniform sampler3D uLut;
        uniform sampler2D uBloom;
        // Independent, much lower-resolution broad-halo layer (HDF only). The tight bright
        // core stays in uBloom; keeping the wide layer separate means heavily blurring the
        // halo can never smear the core into the washed-out gray veil of the earlier design.
        uniform sampler2D uBloomWide;
        uniform mat4 uInputTransform;
        uniform vec3 uDomainMin;
        uniform vec3 uDomainMax;
        uniform float uLutSize;
        uniform float uIntensity;
        uniform float uGrain;
        uniform float uVignette;
        uniform vec4 uColor; // saturation, hue, contrast, brightness
        uniform vec4 uDetail; // highlights, shadows, sharpness, clarity
        uniform float uHdf;
        // 0 = Ricoh-style HDF off (identical output); >0 = strength. HDF is pure optical
        // diffusion of highlights near clipping (a tight restrained halo); there is no tone
        // mapping at all, and midtones/shadows are left crisp. (Uniform keeps its legacy name.)
        uniform float uHdr;
        uniform vec3 uWhiteBalanceGain;
        uniform vec2 uHueRotation; // precomputed cosine/sine, +/-20 degrees
        uniform vec2 uImageSize;
        // Per-frame pseudo-random field offset for preview grain; stills pin a constant
        // seed so tiled photo strips share one seamless grain field.
        uniform vec2 uGrainSeed;
        uniform vec2 uBloomSize;
        uniform vec2 uBloomStep;
        uniform int uPass; // 0: image, 1: highlight extraction, 2: separable Gaussian
        in vec2 vUv;
        out vec4 fragColor;
        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        float luminance(vec3 color) { return dot(color, LUMA); }

        vec3 sourceAt(vec2 uv) {
            // Offset in output-image coordinates, then rotate/crop with the camera transform.
            // Photo strips use full-image vUv, so neighborhoods can cross strip boundaries.
            vec2 sourceUv = (uInputTransform * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy;
            return texture(uImage, sourceUv).rgb;
        }

        vec3 grade(vec3 source) {
            // Software WB drift supplements the camera's AWB/preset. Preserve input luminance.
            vec3 balanced = source * uWhiteBalanceGain;
            balanced *= luminance(source) / max(luminance(balanced), 0.00001);
            balanced = clamp(balanced, 0.0, 1.0);
            vec3 domain = clamp((balanced - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
            vec3 lutUv = (domain * (uLutSize - 1.0) + 0.5) / uLutSize;
            vec3 color = mix(balanced, texture(uLut, lutUv).rgb, uIntensity);

            float y = luminance(color);
            vec2 chroma = vec2(color.r - y, color.b - y);
            chroma = mat2(uHueRotation.x, uHueRotation.y, -uHueRotation.y, uHueRotation.x) * chroma;
            chroma *= 1.0 + uColor.x; // -1 removes chroma; +1 doubles it.
            color = vec3(y + chroma.x, y - dot(chroma, vec2(LUMA.r, LUMA.b)) / LUMA.g, y + chroma.y);

            // Tonal controls act on luminance; they do not rotate neutral colors.
            float highMask = smoothstep(0.45, 1.0, y);
            float lowMask = 1.0 - smoothstep(0.0, 0.55, y);
            float tonal = (y - 0.5) * uColor.z * 0.65 + uColor.w * 0.25;
            tonal += uDetail.x * highMask * 0.23 + uDetail.y * lowMask * 0.23;
            return clamp(color + tonal, 0.0, 1.0);
        }

        vec3 brightAt(vec2 uv) {
            vec3 color = grade(sourceAt(uv));
            if (uHdf > 0.0 || uHdr > 0.0) {
                // Key on the BRIGHTEST CHANNEL, not on luma: a red/blue lamp has low luma but
                // a high single channel, and must still bloom (this was the HDF regression —
                // HDF set uHdf but this branch only looked at uHdr, so HDF fell through to the
                // luma knee and coloured point lights were never extracted). The soft knee only
                // admits light approaching clipping, so ordinary skies and midtones stay out
                // and the shadows get zero lift downstream. HDF uses a slightly wider/knee than
                // the HDR look so lamps read clearly at the default strength.
                float key = max(color.r, max(color.g, color.b));
                float lo = uHdf > 0.0 ? 0.72 : 0.74;
                float hi = uHdf > 0.0 ? 0.90 : 0.92;
                return color * smoothstep(lo, hi, key);
            }
            // Defensive only: prepareBloom runs with uHdf/uHdr > 0, so this path is unused.
            return color * smoothstep(0.66, 0.94, luminance(color));
        }

        float localLuma(vec2 uv, vec2 radius) {
            return 0.25 * (
                luminance(grade(sourceAt(uv + vec2(radius.x, 0.0)))) +
                luminance(grade(sourceAt(uv - vec2(radius.x, 0.0)))) +
                luminance(grade(sourceAt(uv + vec2(0.0, radius.y)))) +
                luminance(grade(sourceAt(uv - vec2(0.0, radius.y)))));
        }

        float noise(vec2 p) {
            vec3 q = fract(vec3(p.xyx) * 0.1031);
            q += dot(q, q.yzx + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        void main() {
            if (uPass == 1) {
                // Four subpixel taps reduce aliasing before downsampling extracted highlights.
                vec2 d = 0.25 / uBloomSize;
                vec3 bright = brightAt(vUv + d) + brightAt(vUv - d) +
                    brightAt(vUv + vec2(d.x, -d.y)) + brightAt(vUv + vec2(-d.x, d.y));
                fragColor = vec4(bright * 0.25, 1.0);
                return;
            }
            if (uPass == 2) {
                // Linear filtering combines adjacent Gaussian weights: five taps per pass.
                vec3 glow = texture(uBloom, vUv).rgb * 0.227027027;
                glow += texture(uBloom, vUv + uBloomStep * 1.384615385).rgb * 0.316216216;
                glow += texture(uBloom, vUv - uBloomStep * 1.384615385).rgb * 0.316216216;
                glow += texture(uBloom, vUv + uBloomStep * 3.230769231).rgb * 0.070270270;
                glow += texture(uBloom, vUv - uBloomStep * 3.230769231).rgb * 0.070270270;
                fragColor = vec4(glow, 1.0);
                return;
            }

            vec3 source = sourceAt(vUv);
            vec3 color = grade(source);
            float y = luminance(color);
            vec2 unit = min(uImageSize.x, uImageSize.y) / uImageSize;
            // Uniform branches skip all neighborhood samples for neutral spatial controls.
            if (uDetail.z != 0.0) {
                float detail = y - localLuma(vUv, unit * 0.0012);
                color += clamp(detail * uDetail.z * 0.9, -0.12, 0.12);
            }
            if (uDetail.w != 0.0) {
                float detail = y - localLuma(vUv, unit * 0.008);
                float midtone = smoothstep(0.0, 0.2, y) * (1.0 - smoothstep(0.8, 1.0, y));
                color += clamp(detail * uDetail.w * 0.8, -0.10, 0.10) * midtone;
            }
            // HDF is purely optical diffusion: no tone mapping at all (the old ACES
            // shoulder re-graded the highlights and read as "washed out"). The only
            // effect is the scattered halo added below, keyed on near-clipping light.
            if (uHdf > 0.0 || uHdr > 0.0) {
                vec3 glow = texture(uBloom, vUv).rgb;
                // Screen-style light scattering preserves the original bright center and
                // keeps the dark regions clean (the 1-color factor is load-bearing).
                vec3 scatterGap = 1.0 - clamp(color, 0.0, 1.0);
                if (uHdf > 0.0) {
                    // Two-layer optical diffusion (Black Pro-Mist 1/4 style):
                    //  - tight core keeps the lamp's tint and gains as the blurred highlight
                    //    approaches clipping (the extracted sheet is clamped to RGBA8, so the
                    //    near-clipping excess is driven from post-blur luminance);
                    //  - broad halo is an independent low-resolution layer, mildly desaturated,
                    //    reading as a soft spherical glow around point lamps/bright windows
                    //    without the gray fog a single widened blur produces.
                    // scatterGap (1 - color) is load-bearing for keeping shadows at zero lift.
                    vec3 core = texture(uBloom, vUv).rgb;
                    core *= 1.0 + 0.25 * luminance(core);
                    vec3 wideTex = texture(uBloomWide, vUv).rgb;
                    vec3 wide = mix(vec3(luminance(wideTex)), wideTex, 0.85);
                    color += scatterGap * (core * 1.50 + wide * 0.55) * uHdf;
                }
                if (uHdr > 0.0) {
                    // The actual HDF button (legacy uniform name): same two-layer optical
                    // model as the soft diffusion, tuned a touch tighter. A tinted tight
                    // core plus the INDEPENDENT broad halo is what makes point lamps and
                    // street lights read with a visible glow at the default strength — the
                    // old tight-only, heavily desaturated single halo was the "HDF not
                    // obvious" report. scatterGap still guarantees zero shadow lift.
                    float amount = mix(0.22, 0.68, uHdr);
                    vec3 coreHalo = mix(vec3(luminance(glow)), glow, 0.60);
                    vec3 wideTex = texture(uBloomWide, vUv).rgb;
                    vec3 wideHalo = mix(vec3(luminance(wideTex)), wideTex, 0.75);
                    color += scatterGap * (coreHalo * 0.70 + wideHalo * 0.45) * amount;
                }
            }
            vec2 centered = (vUv - 0.5) * 1.41421356;
            float falloff = smoothstep(0.25, 1.0, dot(centered, centered));
            color *= 1.0 - uVignette * falloff * 0.6;
            if (uGrain > 0.0) {
                // Two-octave film grain: fine 1px speckle plus ~2.6px coarse clusters that
                // survive preview/downscale. The seed swaps in an independent noise field
                // each frame, so amplitude/density stay constant (no static watermark and no
                // temporal drift); stills pin a constant seed and tile seamlessly across strips.
                vec2 px = vUv * uImageSize;
                float fine = noise(floor(px) + uGrainSeed) - 0.5;
                float coarse = noise(floor(px / 2.6) + uGrainSeed * 1.7 + 17.0) - 0.5;
                // Real stocks carry more grain in shadows, less in clipped highlights.
                float grainDensity = mix(1.30, 0.62, smoothstep(0.0, 1.0, luminance(color)));
                float lumaGrain = (fine * 0.55 + coarse * 0.95) * grainDensity;
                color += vec3(lumaGrain) * uGrain * 0.18;
                // Faint chroma grain only for the faster, grittier stocks (kept subtle).
                float chromaGate = smoothstep(0.18, 0.34, uGrain);
                if (chromaGate > 0.0) {
                    float chromaGrain = noise(floor(px) - uGrainSeed + 41.0) - 0.5;
                    color.rb += chromaGrain * uGrain * 0.05 * chromaGate * grainDensity;
                }
            }
            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """
}
