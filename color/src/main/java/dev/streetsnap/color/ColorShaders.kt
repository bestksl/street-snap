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
        uniform vec3 uWhiteBalanceGain;
        uniform vec2 uHueRotation; // precomputed cosine/sine, +/-20 degrees
        uniform vec2 uImageSize;
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
            return color * smoothstep(0.58, 0.94, luminance(color));
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
            if (uHdf > 0.0) {
                // Diffuse bright light over the untouched center detail, instead of blurring it.
                vec3 glow = texture(uBloom, vUv).rgb;
                color += (1.0 - clamp(color, 0.0, 1.0)) * glow * uHdf * 0.60;
            }
            vec2 centered = (vUv - 0.5) * 1.41421356;
            float falloff = smoothstep(0.25, 1.0, dot(centered, centered));
            color *= 1.0 - uVignette * falloff * 0.6;
            float n = noise(floor(vUv * uImageSize)) - 0.5;
            color += n * uGrain * 0.12;
            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """
}
