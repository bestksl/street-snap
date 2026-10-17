package dev.streetsnap.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dev.streetsnap.camera.CaptureResult
import dev.streetsnap.color.ColorEngine
import dev.streetsnap.color.ColorRecipe
import dev.streetsnap.color.CubeLut
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoSaver(private val context: Context) {
    private val softwareVersion by lazy {
        "street-snap ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"
    }
    /** CameraX already crops the JPEG to the PreviewView viewport; only EXIF orientation is applied. */
    suspend fun save(capture: CaptureResult, engine: ColorEngine, recipe: ColorRecipe, customLut: CubeLut?,
        capturedAt: LocalDateTime = LocalDateTime.now()): Uri = withContext(Dispatchers.IO) {
        val sourceFile = capture.jpegFile
        val renderedFile = File.createTempFile("street-snap-render-", ".jpg", context.cacheDir)
        var input: Bitmap? = null
        var upright: Bitmap? = null
        var output: Bitmap? = null
        var pendingUri: Uri? = null
        try {
            val sourceExif = ExifInterface(sourceFile)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "相机未生成有效 JPEG" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000L) { "照片超过首版处理上限（20 MP）" }
            val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }) ?: error("无法读取相机照片")
            input = decoded
            val oriented = orient(decoded, sourceExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            upright = oriented
            if (oriented !== decoded) {
                input = null
                decoded.recycle()
            }
            val rendered = engine.processPhoto(oriented, recipe, customLut)
            output = rendered
            if (rendered !== oriented) {
                if (input === oriented) input = null
                upright = null
                oriented.recycle()
            }
            renderedFile.outputStream().use { stream ->
                check(rendered.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG 编码失败" }
            }
            val destinationExif = ExifInterface(renderedFile)
            // Keep photographic metadata only. Location, vendor thumbnails and offsets are never copied.
            metadataTags.forEach { tag -> sourceExif.getAttribute(tag)?.let { destinationExif.setAttribute(tag, it) } }
            destinationExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            destinationExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, rendered.width.toString())
            destinationExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, rendered.height.toString())
            destinationExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, rendered.width.toString())
            destinationExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, rendered.height.toString())
            capture.settings.focalLength.equivalentMm?.let {
                destinationExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString())
            }
            destinationExif.setAttribute(ExifInterface.TAG_SOFTWARE, softwareVersion)
            destinationExif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1") // Output is explicitly converted to sRGB.
            destinationExif.saveAttributes()

            val name = "STREET_${capturedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/street-snap")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册文件")
            pendingUri = uri
            resolver.openOutputStream(uri, "w")?.use { stream -> renderedFile.inputStream().use { it.copyTo(stream) } }
                ?: error("无法写入相册")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                "照片未能完成保存"
            }
            pendingUri = null
            uri
        } catch (error: OutOfMemoryError) {
            throw IOException("照片处理内存不足，请稍候重试", error)
        } finally {
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            // The renderer may return its input for an identity recipe; recycle each allocation once.
            listOfNotNull(input, upright, output).distinctBy { System.identityHashCode(it) }.forEach { it.recycle() }
            sourceFile.delete()
            renderedFile.delete()
        }
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private val metadataTags = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    )
}
