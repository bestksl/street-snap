package dev.streetsnap.color

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.io.Closeable
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal data class ColorState(val recipe: ColorRecipe, val lut: CubeLut)

/** All calls, including release, must happen on the context's owning thread. */
internal class GlRenderer(private val externalInput: Boolean) : Closeable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var anchor: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var inputTexture = 0
    private var lutTexture = 0
    private var uploadedLut: CubeLut? = null
    private var lutMinimum = floatArrayOf(0f, 0f, 0f)
    private var lutMaximum = floatArrayOf(1f, 1f, 1f)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val windows = mutableSetOf<EGLSurface>()
    private val uniforms = mutableMapOf<String, Int>()
    private val bloomTextures = IntArray(2)
    private val bloomBuffers = IntArray(2)
    private var bloomWidth = 0
    private var bloomHeight = 0
    private var bloomValid = false
    // Smoothed HDR level for preview frames so AE jumps/toggles cannot pop the halo.
    // Stills are fresh renderers and settle immediately on the first draw.
    private var hdrLevel = 0f
    // Preview swaps in an independent grain field each frame; stills pin frame 1 so every
    // tiled strip shares one seamless field.
    private var grainFrame = 0L
    val textureId get() = inputTexture

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "无法取得 EGL Display" }
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "EGL 初始化失败" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "设备没有可用的 OpenGL ES 3 配置"
            }
            config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3 上下文创建失败" }
            anchor = createPbuffer(1, 1)
            makeCurrent(anchor)
            program = createProgram(ColorShaders.vertex, ColorShaders.fragment(externalInput))
            val names = IntArray(2)
            GLES30.glGenTextures(2, names, 0)
            inputTexture = names[0]
            lutTexture = names[1]
            val target = if (externalInput) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D
            GLES30.glBindTexture(target, inputTexture)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            checkGl("初始化纹理")
        } catch (error: Throwable) {
            close()
            throw ColorProcessingException("调色 GPU 初始化失败", error)
        }
    }

    fun createWindow(surface: Surface): EGLSurface {
        val result = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "无法连接相机输出 Surface" }
        windows += result
        return result
    }

    fun createPbuffer(width: Int, height: Int): EGLSurface {
        val result = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(result != EGL14.EGL_NO_SURFACE) { "离屏 Surface 创建失败" }
        windows += result
        return result
    }

    fun releaseSurface(surface: EGLSurface) {
        if (windows.remove(surface)) {
            // Context loss must not prevent the remaining surfaces from being released.
            if (surface != anchor) makeCurrentForCleanup()
            runCatching { EGL14.eglDestroySurface(display, surface) }
        }
    }

    fun makeCurrent(surface: EGLSurface = anchor) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL 上下文绑定失败" }
    }

    fun uploadBitmap(bitmap: Bitmap) {
        check(!externalInput)
        val maximum = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maximum, 0)
        require(bitmap.width <= maximum[0] && bitmap.height <= maximum[0]) { "照片尺寸超出 GPU 支持范围" }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bloomValid = false
        checkGl("上传照片")
    }

    fun createExternalTexture(): Int {
        check(externalInput)
        makeCurrent()
        val names = IntArray(1)
        GLES30.glGenTextures(1, names, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        checkGl("创建相机纹理")
        return names[0]
    }

    fun deleteExternalTexture(id: Int) {
        // A lost context already owns no usable GL resources; context destruction handles them.
        if (makeCurrentForCleanup()) {
            runCatching { GLES30.glDeleteTextures(1, intArrayOf(id), 0) }
        }
    }

    fun draw(
        surface: EGLSurface,
        width: Int,
        height: Int,
        state: ColorState,
        transform: FloatArray = identity,
        fullWidth: Int = width,
        fullHeight: Int = height,
        tileTop: Int = 0,
        sourceTexture: Int = inputTexture,
    ) {
        makeCurrent(surface)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (externalInput) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glUniform1i(uniform("uImage"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        if (uploadedLut !== state.lut) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F,
                state.lut.size, state.lut.size, state.lut.size, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, state.lut.toRgbaBuffer())
            checkGl("上传 LUT")
            uploadedLut = state.lut
            lutMinimum = state.lut.domainMin
            lutMaximum = state.lut.domainMax
        }
        GLES30.glUniform1i(uniform("uLut"), 1)
        GLES30.glUniform1f(uniform("uLutSize"), state.lut.size.toFloat())
        GLES30.glUniform3fv(uniform("uDomainMin"), 1, lutMinimum, 0)
        GLES30.glUniform3fv(uniform("uDomainMax"), 1, lutMaximum, 0)
        GLES30.glUniform1f(uniform("uIntensity"), state.recipe.intensity)
        GLES30.glUniform1f(uniform("uGrain"), state.recipe.grain)
        GLES30.glUniform1f(uniform("uVignette"), state.recipe.vignette)
        GLES30.glUniform4f(uniform("uColor"), state.recipe.saturation, state.recipe.hue,
            state.recipe.contrast, state.recipe.brightness)
        GLES30.glUniform4f(uniform("uDetail"), state.recipe.highlights, state.recipe.shadows,
            state.recipe.sharpness, state.recipe.clarity)
        GLES30.glUniform1f(uniform("uHdf"), if (state.recipe.hdfEnabled) state.recipe.hdfStrength else 0f)
        val hdrTarget = if (state.recipe.hdrEnabled) state.recipe.hdrStrength.coerceIn(0f, 1f) else 0f
        hdrLevel = if (externalInput) hdrLevel + (hdrTarget - hdrLevel) * 0.2f else hdrTarget
        GLES30.glUniform1f(uniform("uHdr"), hdrLevel)
        val amber = state.recipe.whiteBalanceAmber / 9f
        val green = state.recipe.whiteBalanceGreen / 9f
        GLES30.glUniform3f(uniform("uWhiteBalanceGain"),
            2f.pow(amber * .35f - green * .12f), 2f.pow(green * .22f),
            2f.pow(-amber * .35f - green * .12f))
        val angle = state.recipe.hue * .34906585f
        GLES30.glUniform2f(uniform("uHueRotation"), cos(angle), sin(angle))
        GLES30.glUniform2f(uniform("uImageSize"), fullWidth.toFloat(), fullHeight.toFloat())
        val grainSeedFrame = if (externalInput) ++grainFrame else 1L
        GLES30.glUniform2f(uniform("uGrainSeed"),
            grainSeedHash(grainSeedFrame, 0x9E3779B9L), grainSeedHash(grainSeedFrame, 0x85EBCA6BL))
        GLES30.glUniformMatrix4fv(uniform("uInputTransform"), 1, false, transform, 0)
        val diffusionOn = state.recipe.hdfEnabled && state.recipe.hdfStrength > 0f
        val hdrOn = state.recipe.hdrEnabled && state.recipe.hdrStrength > 0f
        GLES30.glUniform1i(uniform("uBloom"), 2)
        if (diffusionOn || hdrOn) {
            // Photo strips share a single whole-image bloom texture. Preview updates every frame.
            if (externalInput || tileTop == 0 || !bloomValid) prepareBloom(fullWidth, fullHeight, hdrOn)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTextures[0])
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUniform1i(uniform("uPass"), 0)
        GLES30.glUniform4f(uniform("uTile"), 0f, tileTop.toFloat() / fullHeight, 1f, height.toFloat() / fullHeight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("渲染影调")
    }

    /** At most two 384px-long-edge RGBA textures, reused until output aspect/size changes. */
    private fun prepareBloom(width: Int, height: Int, wideHalo: Boolean) {
        val scale = minOf(1f, 384f / maxOf(width, height))
        val targetWidth = maxOf(1, (width * scale).toInt())
        val targetHeight = maxOf(1, (height * scale).toInt())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        if (targetWidth != bloomWidth || targetHeight != bloomHeight) {
            releaseBloom()
            GLES30.glGenTextures(2, bloomTextures, 0)
            GLES30.glGenFramebuffers(2, bloomBuffers, 0)
            for (index in 0..1) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTextures[index])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, targetWidth, targetHeight,
                    0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomBuffers[index])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, bloomTextures[index], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    "柔光 GPU 缓冲区创建失败"
                }
            }
            bloomWidth = targetWidth
            bloomHeight = targetHeight
        }
        GLES30.glViewport(0, 0, bloomWidth, bloomHeight)
        GLES30.glUniform4f(uniform("uTile"), 0f, 0f, 1f, 1f)
        GLES30.glUniform2f(uniform("uBloomSize"), bloomWidth.toFloat(), bloomHeight.toFloat())

        // Extract from the same source transform and grade as the final image.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomBuffers[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTextures[1])
        GLES30.glUniform1i(uniform("uPass"), 1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glUniform1i(uniform("uPass"), 2)
        // Two successive scales widen the visible halo and soften sparse point highlights.
        // Both run only on the bounded bloom textures, reusing the same two buffers.
        // HDF adds two only-slightly-wider scales on the same FBO pair (zero new textures)
        // for a soft core with a short outer halo — a real HDF spreads tightly, so the old
        // .026/.042 HDR-look radii were reined in; see docs/hdr-film-research.md.
        val fractions = if (wideHalo) floatArrayOf(.008f, .014f, .020f, .030f)
        else floatArrayOf(.008f, .014f)
        for (fraction in fractions) {
            val radius = minOf(bloomWidth, bloomHeight) * fraction
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomBuffers[1])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTextures[0])
            GLES30.glUniform2f(uniform("uBloomStep"), radius / bloomWidth, 0f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomBuffers[0])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTextures[1])
            GLES30.glUniform2f(uniform("uBloomStep"), 0f, radius / bloomHeight)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        checkGl("扩散高光")
        bloomValid = true
    }

    private fun releaseBloom() {
        GLES30.glDeleteTextures(2, bloomTextures, 0)
        GLES30.glDeleteFramebuffers(2, bloomBuffers, 0)
        bloomTextures.fill(0)
        bloomBuffers.fill(0)
        bloomWidth = 0
        bloomHeight = 0
        bloomValid = false
    }

    fun present(surface: EGLSurface, timestampNanos: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, surface, timestampNanos)
        check(EGL14.eglSwapBuffers(display, surface)) { "相机预览提交失败" }
    }

    private fun uniform(name: String) = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (makeCurrentForCleanup()) {
            if (program != 0) runCatching { GLES30.glDeleteProgram(program) }
            runCatching { releaseBloom() }
            runCatching { GLES30.glDeleteTextures(2, intArrayOf(inputTexture, lutTexture), 0) }
        }
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        windows.forEach { runCatching { EGL14.eglDestroySurface(display, it) } }
        windows.clear()
        if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
        // eglTerminate invalidates other contexts using this display, including the preview.
        // Context/surfaces are owned here, while the process-wide display is left initialized.
        runCatching { EGL14.eglReleaseThread() }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        anchor = EGL14.EGL_NO_SURFACE
    }

    /** Cleanup cannot assume the EGL context survived the error that initiated it. */
    private fun makeCurrentForCleanup(): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT || anchor == EGL14.EGL_NO_SURFACE) return false
        return runCatching { EGL14.eglMakeCurrent(display, anchor, anchor, context) }.getOrDefault(false)
    }

    companion object {
        // Deterministic per-frame grain seed in [0,1): splitmix-style mixing keeps successive
        // frames (and the two salted channels) decorrelated without GPU sin precision issues.
        private fun grainSeedHash(frame: Long, salt: Long): Float {
            var h = (frame xor salt) * 1274126177L
            h = h xor (h ushr 29)
            h *= 2654435761L
            h = h xor (h ushr 33)
            return (h ushr 40).toFloat() / 16777216f // 24 bits
        }

        fun checkGl(operation: String) {
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) { "$operation 失败 (GL 0x${error.toString(16)})" }
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error("Shader 编译失败：$log")
            }
            return shader
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = try { compile(GLES30.GL_FRAGMENT_SHADER, fragment) }
            catch (error: Throwable) { GLES30.glDeleteShader(vs); throw error }
            val result = GLES30.glCreateProgram()
            try {
                GLES30.glAttachShader(result, vs)
                GLES30.glAttachShader(result, fs)
                GLES30.glLinkProgram(result)
                val status = IntArray(1)
                GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "Shader 链接失败：${GLES30.glGetProgramInfoLog(result)}" }
                return result
            } catch (error: Throwable) {
                GLES30.glDeleteProgram(result)
                throw error
            } finally {
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
            }
        }

    }
}
