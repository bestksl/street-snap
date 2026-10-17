package dev.streetsnap.camera

import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal data class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
internal data class SampledRgbFrame(val pixels: IntArray, val geometry: CompositionFrameGeometry)

/** Low-resolution analysis conversion only; never used for the saved photo's colour rendering. */
internal object YuvFrameSampler {
    fun copy(sourceWidth: Int, sourceHeight: Int, crop: FrameCrop, rotation: Int,
        maximumEdge: Int, planes: List<YuvPlane>): SampledRgbFrame {
        require(planes.size == 3 && maximumEdge > 0)
        val scale = minOf(1f, maximumEdge.toFloat() / maxOf(crop.width, crop.height))
        val width = (crop.width * scale).roundToInt().coerceAtLeast(1)
        val height = (crop.height * scale).roundToInt().coerceAtLeast(1)
        val swap = rotation == 90 || rotation == 270
        val outputWidth = if (swap) height else width
        val outputHeight = if (swap) width else height
        val geometry = CompositionFrameGeometry(sourceWidth, sourceHeight, crop, rotation, outputWidth, outputHeight)
        val buffers = planes.map { it.buffer.duplicate() }
        val bases = buffers.map { it.position() }
        fun channel(index: Int, x: Int, y: Int): Int = buffers[index].get(
            bases[index] + y * planes[index].rowStride + x * planes[index].pixelStride,
        ).toInt() and 255
        val pixels = IntArray(outputWidth * outputHeight)
        for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
            val rawX = when (rotation) {
                90 -> y
                180 -> width - 1 - x
                270 -> width - 1 - y
                else -> x
            }
            val rawY = when (rotation) {
                90 -> height - 1 - x
                180 -> height - 1 - y
                270 -> x
                else -> y
            }
            val sx = (crop.left + (rawX + 0.5f) * crop.width / width).toInt().coerceAtMost(crop.right - 1)
            val sy = (crop.top + (rawY + 0.5f) * crop.height / height).toInt().coerceAtMost(crop.bottom - 1)
            val luma = (channel(0, sx, sy) - 16).coerceAtLeast(0)
            val u = channel(1, sx / 2, sy / 2) - 128
            val v = channel(2, sx / 2, sy / 2) - 128
            val red = ((298 * luma + 409 * v + 128) shr 8).coerceIn(0, 255)
            val green = ((298 * luma - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val blue = ((298 * luma + 516 * u + 128) shr 8).coerceIn(0, 255)
            pixels[y * outputWidth + x] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return SampledRgbFrame(pixels, geometry)
    }
}
