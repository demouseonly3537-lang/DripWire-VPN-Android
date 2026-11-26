package cc.dripwire.vpn.support

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turns whatever photo the user picked (any format/size/orientation the gallery hands back)
 * into a small, backend-friendly JPEG: downsamples to a sane max dimension, re-encodes as JPEG
 * (sidesteps HEIC/PNG-vs-mime mismatches entirely), and shrinks the JPEG quality until it's
 * under the backend's 5 MB upload limit. Returns null if the image couldn't be decoded at all,
 * or [PreparedImage.tooLarge] = true if even the lowest quality is still over the limit.
 */
object ImageUtils {

    private const val MAX_DIMENSION_PX = 1280
    private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    private val JPEG_QUALITY_STEPS = intArrayOf(85, 70, 55, 40)

    data class PreparedImage(val base64: String, val mime: String, val filename: String)

    /** Returns the prepared image, or null if the file couldn't be read/decoded as an image. */
    fun prepareForUpload(context: Context, uri: Uri): PreparedImage? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_DIMENSION_PX ||
            bounds.outHeight / (sampleSize * 2) >= MAX_DIMENSION_PX
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: return null

        val scaled = if (bitmap.width > MAX_DIMENSION_PX || bitmap.height > MAX_DIMENSION_PX) {
            val scale = MAX_DIMENSION_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        var bytes: ByteArray? = null
        for (quality in JPEG_QUALITY_STEPS) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val candidate = out.toByteArray()
            if (candidate.size <= MAX_UPLOAD_BYTES) {
                bytes = candidate
                break
            }
            bytes = candidate // keep the smallest attempt so far in case all steps are still too big
        }
        scaled.recycle()

        val finalBytes = bytes ?: return null
        if (finalBytes.size > MAX_UPLOAD_BYTES) return null // still too large even at lowest quality

        val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
        return PreparedImage(base64 = base64, mime = "image/jpeg", filename = "photo.jpg")
    }
}
