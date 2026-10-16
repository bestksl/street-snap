package dev.streetsnap.color

import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.Looper
import androidx.camera.core.CameraEffect
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shared, immutable settings for preview and still rendering. No per-frame Bitmap is created. */
class ColorEngine : Closeable {
    private data class Settings(val recipe: ColorRecipe, val custom: CubeLut?)
    private val settings = AtomicReference(Settings(ColorRecipe(), null))
    private val closed = AtomicBoolean(false)
    private val photoLock = Any()
    private val previewLock = Any()
    private var processor: PreviewColorProcessor? = null
    private var effect: CameraEffect? = null

    val recipe: ColorRecipe get() = settings.get().recipe

    fun updateRecipe(recipe: ColorRecipe) {
        check(!closed.get()) { "ColorEngine 已关闭" }
        while (true) {
            val old = settings.get()
            resolve(recipe, old.custom)
            if (settings.compareAndSet(old, old.copy(recipe = recipe))) return
        }
    }

    /** Imports are immutable. Select CUSTOM explicitly after successfully loading a LUT. */
    fun setCustomLut(lut: CubeLut?) {
        check(!closed.get()) { "ColorEngine 已关闭" }
        while (true) {
            val old = settings.get()
            resolve(old.recipe, lut)
            if (settings.compareAndSet(old, old.copy(custom = lut))) return
        }
    }

    /** Add this stable effect to CameraX UseCaseGroup. The effect targets SDR preview only. */
    fun previewEffect(onError: (Throwable) -> Unit = {}): CameraEffect = synchronized(previewLock) {
        check(!closed.get()) { "ColorEngine 已关闭" }
        if (processor?.hasFailed == true) {
            processor?.close()
            processor = null
            effect = null
        }
        effect ?: PreviewColorProcessor(
            state = { settings.get().let { resolve(it.recipe, it.custom) } },
            onError = onError,
        ).let { created ->
            processor = created
            created.effect.also { effect = it }
        }
    }

    /** Unbind/rebind CameraX with the returned effect; active photo processing is unaffected. */
    fun resetPreviewEffect(onError: (Throwable) -> Unit = {}): CameraEffect = synchronized(previewLock) {
        check(!closed.get()) { "ColorEngine 已关闭" }
        processor?.close()
        processor = null
        effect = null
        previewEffect(onError)
    }

    /** Runs off the UI thread. An unchanged identity recipe returns [input] without copying it. */
    fun processPhoto(input: Bitmap): Bitmap {
        val snapshot = settings.get()
        return processPhoto(input, snapshot.recipe, snapshot.custom)
    }

    /** Use shutter-time recipe/LUT snapshots while allowing preview controls to keep changing. */
    fun processPhoto(input: Bitmap, recipe: ColorRecipe, customLut: CubeLut? = null): Bitmap {
        check(!closed.get()) { "ColorEngine 已关闭" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "照片调色必须在后台线程执行" }
        require(!input.isRecycled && input.config != Bitmap.Config.HARDWARE) { "需要有效的软件 Bitmap" }
        require(input.width.toLong() * input.height <= MAX_PHOTO_PIXELS) {
            "照片不能超过 ${MAX_PHOTO_PIXELS / 1_000_000}MP，请在解码时缩小尺寸"
        }
        val state = resolve(recipe, customLut)
        if (recipe.isIdentity) return input
        return synchronized(photoLock) {
            try {
                renderPhoto(input, state)
            } catch (error: Throwable) {
                if (error is ColorProcessingException) throw error
                throw ColorProcessingException("照片调色失败，请重试", error)
            }
        }
    }

    private fun renderPhoto(input: Bitmap, state: ColorState): Bitmap {
        var output: Bitmap? = null
        try {
            GlRenderer(externalInput = false).use { renderer ->
                // Only one source texture and a small readback strip; no full-size framebuffer.
                renderer.uploadBitmap(input)
                val stripHeight = minOf(STRIP_HEIGHT, input.height)
                val target = renderer.createPbuffer(input.width, stripHeight)
                output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
                val bytes = ByteBuffer.allocateDirect(input.width * stripHeight * 4).order(ByteOrder.BIG_ENDIAN)
                val pixels = IntArray(input.width * stripHeight)
                var top = 0
                while (top < input.height) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("照片调色已取消")
                    val count = minOf(stripHeight, input.height - top)
                    renderer.draw(target, input.width, count, state,
                        fullWidth = input.width, fullHeight = input.height, tileTop = top)
                    bytes.clear()
                    GLES30.glReadPixels(0, 0, input.width, count, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
                    GlRenderer.checkGl("读取照片")
                    val packed = bytes.asIntBuffer()
                    for (i in 0 until input.width * count) {
                        val rgba = packed.get(i)
                        pixels[i] = (rgba ushr 8) or (rgba shl 24)
                    }
                    // GL texture row 0 is bitmap row 0; readback preserves bitmap row order.
                    output!!.setPixels(pixels, 0, input.width, 0, top, input.width, count)
                    top += count
                }
                return output!!
            }
        } catch (error: Throwable) {
            output?.recycle()
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(previewLock) { processor?.close() }
    }

    private fun resolve(recipe: ColorRecipe, custom: CubeLut?): ColorState = ColorState(
        recipe,
        when (recipe.preset) {
            ColorPreset.ORIGINAL -> BuiltInLuts.original
            ColorPreset.POSITIVE -> BuiltInLuts.positive
            ColorPreset.NEGATIVE -> BuiltInLuts.negative
            ColorPreset.POWDER_BLUE -> BuiltInLuts.powderBlue
            ColorPreset.MONO -> BuiltInLuts.mono
            ColorPreset.SUNNY -> BuiltInLuts.sunny
            ColorPreset.CUSTOM -> requireNotNull(custom) { "请先导入有效的 .cube LUT" }
        },
    )

    companion object {
        const val MAX_PHOTO_PIXELS = 20_000_000L
        private const val STRIP_HEIGHT = 128
    }
}
