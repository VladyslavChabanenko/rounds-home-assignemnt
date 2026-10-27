package io.github.vladyslavchabanenko.imageloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The bytes are not an image the platform can decode. */
internal class InvalidImageException(
    message: String,
) : Exception(message)

/** Decodes image bytes at no more than the size a target needs, never upscaling. */
internal object BitmapDecoder {
    /**
     * Decodes [bytes] into a bitmap that covers [targetWidth] x [targetHeight] but is no larger
     * than [coverSize] of the source. Runs CPU-heavy work, so call it off the main thread.
     */
    fun decode(
        bytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw InvalidImageException("The data is not a decodable image")
        }

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val sampled =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw InvalidImageException("The platform could not decode the image")

        val cover = coverSize(sampled.width, sampled.height, targetWidth, targetHeight)
        if (cover.width >= sampled.width && cover.height >= sampled.height) return sampled
        val scaled = Bitmap.createScaledBitmap(sampled, cover.width, cover.height, true)
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    /** The largest power of two that keeps both sampled dimensions at or above the requested ones. */
    fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val minWidth = reqWidth.coerceAtLeast(1)
        val minHeight = reqHeight.coerceAtLeast(1)
        var sampleSize = 1
        while (srcWidth / (sampleSize * 2) >= minWidth && srcHeight / (sampleSize * 2) >= minHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * The smallest size with the source's aspect ratio that covers the requested size. It is the
     * source size when covering would need upscaling.
     */
    fun coverSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): PixelSize {
        if (reqWidth >= srcWidth || reqHeight >= srcHeight) return PixelSize(srcWidth, srcHeight)
        val widthBinds = reqWidth.toLong() * srcHeight >= reqHeight.toLong() * srcWidth
        return if (widthBinds) {
            PixelSize(reqWidth, ceilDiv(srcHeight.toLong() * reqWidth, srcWidth))
        } else {
            PixelSize(ceilDiv(srcWidth.toLong() * reqHeight, srcHeight), reqHeight)
        }
    }

    private fun ceilDiv(
        dividend: Long,
        divisor: Int,
    ): Int = ((dividend + divisor - 1) / divisor).toInt()
}
