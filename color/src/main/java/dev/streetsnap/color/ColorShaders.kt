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
        // 0 = HDR look off (identical output); >0 = strength, driving the highlight
        // shoulder amount, the wider bloom passes and the halo weight.
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

        // Narkowicz ACES filmic fit: keeps hue in the highlight roll-off (UE4 default).
        vec3 acesFilm(vec3 x) {
            const float a = 2.51;
            const float b = 0.03;
            const float c = 2.43;
            const float d = 0.59;
            const float e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }

        vec3 brightAt(vec2 uv) {
            vec3 color = grade(sourceAt(uv));
            if (uHdr > 0.0) {
                // HDR path keys on the brightest channel (colored neon keeps its color)
                // with a lower, tighter knee so the wide halo has something to spread.
                float key = max(color.r, max(color.g, color.b));
                return color * smoothstep(0.57, 0.72, key);
            }
            // A soft high-light knee excludes middle gray, so wider diffusion does not wash
            // an evenly lit scene with a gray veil. Exposure still determines light sources.
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
            if (uHdr > 0.0) {
                // LDR has no headroom above 1.0: lift only the bright region first, then
                // roll it through the ACES shoulder so clipped whites keep soft color.
                // Midtones/shadows stay untouched; strength grows shoulder and lift together.
                float shoulderMask = smoothstep(0.50, 0.90, y);
                float shoulderAmount = mix(0.45, 0.85, uHdr) * shoulderMask;
                float headroom = 1.0 + (mix(1.10, 1.60, uHdr) - 1.0) * shoulderMask;
                vec3 toned = acesFilm(color * headroom);
                color = mix(color, toned, shoulderAmount);
            }
            if (uHdf > 0.0 || uHdr > 0.0) {
                vec3 glow = texture(uBloom, vUv).rgb;
                // Screen-style light scattering preserves the original bright center and
                // keeps the dark regions clean (the 1-color factor is load-bearing).
                vec3 scatterGap = 1.0 - clamp(color, 0.0, 1.0);
                if (uHdf > 0.0) {
                    // Diffuse bright light over the untouched center detail, instead of blurring it.
                    // The broader bloom is visible around lamps/windows even at the default 0.4.
                    // HDF-only output stays exactly as before (no HDR desaturation applied).
                    color += scatterGap * glow * uHdf * 1.25;
                }
                if (uHdr > 0.0) {
                    // Slight desaturation keeps the wide outer halo from staining midtones;
                    // wide and deliberately stronger than HDF's default as asked.
                    vec3 halo = mix(vec3(luminance(glow)), glow, 0.85);
                    color += scatterGap * halo * mix(0.90, 1.90, uHdr);
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
