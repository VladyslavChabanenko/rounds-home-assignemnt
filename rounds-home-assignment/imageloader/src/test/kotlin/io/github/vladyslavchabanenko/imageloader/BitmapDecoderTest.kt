package io.github.vladyslavchabanenko.imageloader

import io.github.vladyslavchabanenko.imageloader.fakes.jpegBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapDecoderTest {
    @Test
    fun sampleSizeHalvesWhileBothDimensionsStayAboveTarget() {
        assertEquals(2, BitmapDecoder.calculateInSampleSize(4000, 3000, 1080, 600))
    }

    @Test
    fun sampleSizeIsOneWhenSourceIsSmallerThanTarget() {
        assertEquals(1, BitmapDecoder.calculateInSampleSize(300, 200, 1080, 600))
    }

    @Test
    fun sampleSizeTakesTheLargerPowerOfTwoOnAnExactBoundary() {
        assertEquals(2, BitmapDecoder.calculateInSampleSize(2000, 1000, 1000, 500))
        assertEquals(4, BitmapDecoder.calculateInSampleSize(4000, 2000, 1000, 500))
    }

    @Test
    fun coverSizeNeverUpscales() {
        assertEquals(PixelSize(100, 50), BitmapDecoder.coverSize(100, 50, 200, 100))
        assertEquals(PixelSize(100, 50), BitmapDecoder.coverSize(100, 50, 100, 50))
    }

    @Test
    fun coverSizeKeepsAspectRatioAndMatchesTheBindingDimension() {
        assertEquals(PixelSize(200, 100), BitmapDecoder.coverSize(1000, 500, 200, 100))
        assertEquals(PixelSize(200, 150), BitmapDecoder.coverSize(1600, 1200, 200, 100))
    }

    @Test
    fun coverSizeCoversBothTargetDimensions() {
        val size = BitmapDecoder.coverSize(1920, 1080, 100, 300)

        assertEquals(300, size.height)
        assertTrue(size.width >= 100)
    }

    @Test
    fun decodedJpegCoversTargetAndIsNoLargerThanCoverSize() {
        val bitmap = BitmapDecoder.decode(jpegBytes(1920, 1080), 540, 300)

        val cover = BitmapDecoder.coverSize(1920, 1080, 540, 300)
        assertTrue(bitmap.width >= 540 && bitmap.height >= 300)
        assertTrue(bitmap.width <= cover.width && bitmap.height <= cover.height)
    }

    @Test
    fun decodedJpegSmallerThanTargetIsNotUpscaled() {
        val bitmap = BitmapDecoder.decode(jpegBytes(100, 50), 540, 300)

        assertEquals(100, bitmap.width)
        assertEquals(50, bitmap.height)
    }

    @Test
    fun nonImageBytesThrowInvalidImageException() {
        assertThrows(InvalidImageException::class.java) {
            BitmapDecoder.decode("definitely not an image".toByteArray(), 100, 100)
        }
    }

    @Test
    fun emptyBytesThrowInvalidImageException() {
        assertThrows(InvalidImageException::class.java) {
            BitmapDecoder.decode(ByteArray(0), 100, 100)
        }
    }
}
