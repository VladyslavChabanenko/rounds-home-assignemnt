package io.github.vladyslavchabanenko.imageloader.fakes

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * JPEG bytes of a [width] x [height] image filled with [color]. Works only under Robolectric
 * with `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
 */
fun jpegBytes(
    width: Int,
    height: Int,
    color: Int = Color.RED,
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}
