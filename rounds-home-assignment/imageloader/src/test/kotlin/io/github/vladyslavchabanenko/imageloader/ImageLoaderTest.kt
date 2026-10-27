@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.vladyslavchabanenko.imageloader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View.MeasureSpec
import android.widget.ImageView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.vladyslavchabanenko.imageloader.fakes.FakeDownloader
import io.github.vladyslavchabanenko.imageloader.fakes.FakeLifecycleOwner
import io.github.vladyslavchabanenko.imageloader.fakes.LifecycleContext
import io.github.vladyslavchabanenko.imageloader.fakes.jpegBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloader = FakeDownloader()
    private val owner = FakeLifecycleOwner()
    private val placeholder = ColorDrawable(Color.GRAY)
    private val errorDrawable = ColorDrawable(Color.MAGENTA)
    private val url = "https://example.test/first.jpg"
    private val otherUrl = "https://example.test/second.jpg"

    private fun TestScope.createLoader(): ImageLoader {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ImageLoader(downloader, dispatcher, dispatcher)
    }

    private fun newImageView(
        viewContext: Context = context,
        treeOwner: LifecycleOwner? = owner,
    ): ImageView =
        ImageView(viewContext).apply {
            layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
            treeOwner?.let { setViewTreeLifecycleOwner(it) }
        }

    /**
     * Setting a drawable of another intrinsic size calls requestLayout(), and layout() with the
     * same bounds notifies no listener unless measure() ran first.
     */
    private fun ImageView.relayout() {
        measure(
            MeasureSpec.makeMeasureSpec(VIEW_WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, MeasureSpec.EXACTLY),
        )
        layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
    }

    private fun ImageView.shownBitmap() = (drawable as BitmapDrawable).bitmap

    private fun assertMostlyRed(view: ImageView) {
        val pixel = view.shownBitmap().getPixel(0, 0)
        assertTrue(Color.red(pixel) > 200 && Color.blue(pixel) < 80)
    }

    private fun assertMostlyBlue(view: ImageView) {
        val pixel = view.shownBitmap().getPixel(0, 0)
        assertTrue(Color.blue(pixel) > 200 && Color.red(pixel) < 80)
    }

    @Test
    fun drawableOverloadShowsThePlaceholderBeforeAnyDispatcherAdvances() =
        runTest {
            val loader = createLoader()
            val view = newImageView()

            loader.load(url, view, placeholder, errorDrawable)

            assertSame(placeholder, view.drawable)
            assertEquals(0, downloader.callCount(url))
        }

    @Test
    fun resourceOverloadShowsThePlaceholderBeforeAnyDispatcherAdvances() =
        runTest {
            val loader = createLoader()
            val view = newImageView()

            loader.load(url, view, android.R.drawable.ic_menu_gallery, android.R.drawable.ic_delete)

            assertNotNull(view.drawable)
            assertEquals(0, downloader.callCount(url))
        }

    @Test
    fun successShowsABitmapThatCoversTheViewWithoutExceedingTheCoverSize() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, jpegBytes(1920, 1080, Color.RED))

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()

            val bitmap = view.shownBitmap()
            val cover = BitmapDecoder.coverSize(1920, 1080, VIEW_WIDTH, VIEW_HEIGHT)
            assertTrue(bitmap.width >= VIEW_WIDTH && bitmap.height >= VIEW_HEIGHT)
            assertTrue(bitmap.width <= cover.width && bitmap.height <= cover.height)
            assertMostlyRed(view)
        }

    @Test
    fun resourceOverloadShowsTheDownloadedImage() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, jpegBytes(400, 200, Color.BLUE))

            loader.load(url, view, android.R.drawable.ic_menu_gallery, android.R.drawable.ic_delete)
            view.relayout()
            advanceUntilIdle()

            assertMostlyBlue(view)
        }

    @Test
    fun downloadFailureShowsTheErrorDrawable() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.fail(url, IOException("HTTP 403"))

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()

            assertSame(errorDrawable, view.drawable)
        }

    @Test
    fun nonImageBytesShowTheErrorDrawable() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, "not an image".toByteArray())

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()

            assertSame(errorDrawable, view.drawable)
        }

    @Test
    fun failureKeepsThePlaceholderWhenNoErrorImageWasGiven() =
        runTest {
            val loader = createLoader()
            val withoutDrawable = newImageView()
            val withoutResource = newImageView()
            downloader.fail(url, IOException("HTTP 403"))

            loader.load(url, withoutDrawable, placeholder)
            loader.load(url, withoutResource, android.R.drawable.ic_menu_gallery, 0)
            val resourcePlaceholder = withoutResource.drawable
            withoutDrawable.relayout()
            withoutResource.relayout()
            advanceUntilIdle()

            assertSame(placeholder, withoutDrawable.drawable)
            assertSame(resourcePlaceholder, withoutResource.drawable)
            assertEquals(2, downloader.callCount(url))
        }

    @Test
    fun aSecondLoadOnTheSameViewCancelsTheFirstAndNeverShowsItsImage() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            val firstGate = downloader.gate(url)
            downloader.succeed(otherUrl, jpegBytes(400, 200, Color.BLUE))

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()
            loader.load(otherUrl, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()
            firstGate.complete(jpegBytes(400, 200, Color.RED))
            advanceUntilIdle()

            assertEquals(1, downloader.cancelledCount(url))
            assertMostlyBlue(view)
        }

    @Test
    fun destroyingTheViewTreeOwnerCancelsTheDownloadAndKeepsThePlaceholder() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.gate(url)

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()
            owner.destroy()
            advanceUntilIdle()

            assertEquals(1, downloader.cancelledCount(url))
            assertSame(placeholder, view.drawable)
        }

    @Test
    fun loadingIntoAViewWhoseOwnerIsAlreadyDestroyedNeverDownloads() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, jpegBytes(400, 200))
            owner.destroy()

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()

            assertEquals(0, downloader.callCount(url))
            assertSame(placeholder, view.drawable)
        }

    @Test
    fun aViewWithoutAViewTreeOwnerFallsBackToTheContextOwner() =
        runTest {
            val loader = createLoader()
            val contextOwner = FakeLifecycleOwner()
            val view = newImageView(LifecycleContext(context, contextOwner), treeOwner = null)
            downloader.gate(url)

            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()
            contextOwner.destroy()
            advanceUntilIdle()

            assertEquals(1, downloader.cancelledCount(url))
            assertSame(placeholder, view.drawable)
        }

    @Test
    fun aViewTreeOwnerFoundAfterLayoutAlsoCancelsTheRequest() =
        runTest {
            val loader = createLoader()
            val contextOwner = FakeLifecycleOwner()
            val fragmentViewOwner = FakeLifecycleOwner()
            val view = ImageView(LifecycleContext(context, contextOwner))
            downloader.gate(url)

            loader.load(url, view, placeholder, errorDrawable)
            view.setViewTreeLifecycleOwner(fragmentViewOwner)
            view.relayout()
            advanceUntilIdle()
            fragmentViewOwner.destroy()
            advanceUntilIdle()

            assertEquals(1, downloader.cancelledCount(url))
            assertSame(placeholder, view.drawable)
        }

    @Test
    fun aViewThatIsNotLaidOutYetDoesNotDownloadUntilItIsLaidOut() =
        runTest {
            val loader = createLoader()
            val view = ImageView(context).apply { setViewTreeLifecycleOwner(owner) }
            downloader.succeed(url, jpegBytes(1920, 1080))

            loader.load(url, view, placeholder, errorDrawable)
            advanceUntilIdle()
            assertEquals(0, downloader.callCount(url))
            assertNull((view.drawable as? BitmapDrawable)?.bitmap)

            view.relayout()
            advanceUntilIdle()

            assertEquals(1, downloader.callCount(url))
            assertTrue(view.shownBitmap().width >= VIEW_WIDTH)
        }

    private companion object {
        const val VIEW_WIDTH = 200
        const val VIEW_HEIGHT = 100
    }
}
