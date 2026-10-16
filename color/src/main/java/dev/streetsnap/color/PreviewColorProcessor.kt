package dev.streetsnap.color

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX -> OES texture -> shared color/detail shader + optional GPU bloom -> preview surface. */
internal class PreviewColorProcessor(
    private val state: () -> ColorState,
    private val onError: (Throwable) -> Unit,
) : SurfaceProcessor, Closeable {
    private val thread = HandlerThread("street-snap-preview-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { task -> handler.post(task) }
    private val released = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var failureCause: Throwable? = null
    val hasFailed: Boolean get() = failed.get()
    private var renderer: GlRenderer? = null
    private data class Input(
        val texture: SurfaceTexture, val surface: Surface, val textureId: Int, val request: SurfaceRequest
    )
    private data class Output(val output: SurfaceOutput, val eglSurface: EGLSurface)
    private val inputs = mutableSetOf<Input>()
    private val outputs = mutableListOf<Output>()
    private var currentInput: Input? = null
    private var frameScheduled = false
    private val cameraMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    val effect: CameraEffect = object : CameraEffect(
        CameraEffect.PREVIEW, executor, this@PreviewColorProcessor, Consumer { error -> report(error) }
    ) {}

    override fun onInputSurface(request: SurfaceRequest) {
        if (released.get()) {
            request.willNotProvideSurface()
            return
        }
        if (failed.get()) {
            request.willNotProvideSurface()
            throw ProcessingException().apply { initCause(failureCause) }
        }
        var uncommitted: Input? = null
        try {
            check(request.dynamicRange == androidx.camera.core.DynamicRange.SDR) { "一期仅支持 SDR 取景" }
            val gl = renderer ?: GlRenderer(externalInput = true).also { renderer = it }
            gl.makeCurrent()
            currentInput?.texture?.setOnFrameAvailableListener(null)
            val textureId = gl.createExternalTexture()
            val texture = try {
                SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(request.resolution.width, request.resolution.height)
                }
            } catch (error: Throwable) {
                gl.deleteExternalTexture(textureId)
                throw error
            }
            val input = try { Input(texture, Surface(texture), textureId, request) }
            catch (error: Throwable) {
                texture.release()
                gl.deleteExternalTexture(textureId)
                throw error
            }
            uncommitted = input
            inputs += input
            currentInput = input
            texture.setOnFrameAvailableListener({
                if (!frameScheduled && !released.get() && !failed.get() && currentInput === input) {
                    frameScheduled = true
                    handler.post {
                        frameScheduled = false
                        if (!released.get() && !failed.get() && currentInput === input) renderFrame(input)
                    }
                }
            }, handler)
            request.provideSurface(input.surface, executor) {
                releaseInput(input)
                releaseIfFinished()
            }
            uncommitted = null
        } catch (error: Throwable) {
            uncommitted?.let { input ->
                releaseInput(input)
            }
            request.willNotProvideSurface()
            report(error)
            throw ProcessingException().apply { initCause(error) }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released.get()) {
            surfaceOutput.close()
            return
        }
        if (failed.get()) {
            surfaceOutput.close()
            throw ProcessingException().apply { initCause(failureCause) }
        }
        try {
            val gl = renderer ?: GlRenderer(externalInput = true).also { renderer = it }
            var entry: Output? = null
            val surface = surfaceOutput.getSurface(executor) {
                entry?.let { existing ->
                    outputs.remove(existing)
                    releaseOutput(existing)
                }
                runCatching { surfaceOutput.close() }
                releaseIfFinished()
            }
            val created = Output(surfaceOutput, gl.createWindow(surface))
            entry = created
            outputs += created
        } catch (error: Throwable) {
            runCatching { surfaceOutput.close() }
            report(error)
            throw ProcessingException().apply { initCause(error) }
        }
    }

    private fun renderFrame(input: Input) {
        try {
            val gl = renderer ?: return
            gl.makeCurrent()
            input.texture.updateTexImage()
            input.texture.getTransformMatrix(cameraMatrix)
            val snapshot = state()
            for (entry in outputs) {
                entry.output.updateTransformMatrix(outputMatrix, cameraMatrix)
                gl.draw(entry.eglSurface, entry.output.size.width, entry.output.size.height, snapshot, outputMatrix,
                    sourceTexture = input.textureId)
                gl.present(entry.eglSurface, input.texture.timestamp)
            }
        } catch (error: Throwable) {
            report(error)
        }
    }

    private fun report(error: Throwable) {
        if (!released.get() && failed.compareAndSet(false, true)) {
            failureCause = error
            // CameraX must also be told to stop the failed pipeline; the UI can rebind a new effect.
            inputs.forEach { it.request.invalidate() }
            onError(ColorProcessingException("实时影调预览失败", error))
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        handler.post {
            currentInput?.texture?.setOnFrameAvailableListener(null)
            inputs.forEach { it.request.invalidate() }
            outputs.forEach {
                releaseOutput(it)
            }
            outputs.clear()
            releaseIfFinished()
        }
    }

    private fun releaseIfFinished() {
        if (released.get() && inputs.isEmpty()) {
            renderer?.close()
            renderer = null
            thread.quitSafely()
        }
    }

    /** Surface teardown runs after EGL failures too; never let cleanup crash the GL thread. */
    private fun releaseInput(input: Input) {
        if (currentInput === input) currentInput = null
        inputs.remove(input)
        runCatching { input.texture.setOnFrameAvailableListener(null) }
        runCatching { input.surface.release() }
        runCatching { input.texture.release() }
        runCatching { renderer?.deleteExternalTexture(input.textureId) }
    }

    private fun releaseOutput(output: Output) {
        runCatching { renderer?.releaseSurface(output.eglSurface) }
        runCatching { output.output.close() }
    }
}
