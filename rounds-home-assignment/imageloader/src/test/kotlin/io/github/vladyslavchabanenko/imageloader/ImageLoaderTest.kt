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
import io.github.vladyslavchabanenko.imageloader.fakes.FakeClock
import io.github.vladyslavchabanenko.imageloader.fakes.FakeDownloader
import io.github.vladyslavchabanenko.imageloader.fakes.FakeLifecycleOwner
import io.github.vladyslavchabanenko.imageloader.fakes.LifecycleContext
import io.github.vladyslavchabanenko.imageloader.fakes.jpegBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloader = FakeDownloader()
    private val clock = FakeClock()
    private val owner = FakeLifecycleOwner()
    private val placeholder = ColorDrawable(Color.GRAY)
    private val errorDrawable = ColorDrawable(Color.MAGENTA)
    private val url = "https://example.test/first.jpg"
    private val otherUrl = "https://example.test/second.jpg"

    private val diskCache by lazy { newDiskCache() }

    private fun newDiskCache() = DiskCache(lazy { temporaryFolder.root }, clock)

    private fun TestScope.createLoader(
        cache: DiskCache = diskCache,
        decodeDispatcher: CoroutineDispatcher? = null,
    ): ImageLoader {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ImageLoader.create(downloader, cache, clock, MEMORY_CACHE_BYTES, dispatcher, dispatcher, decodeDispatcher ?: dispatcher)
    }

    private fun newImageView(
        viewContext: Context = context,
        treeOwner: LifecycleOwner? = owner,
        width: Int = VIEW_WIDTH,
        height: Int = VIEW_HEIGHT,
    ): ImageView =
        ImageView(viewContext).apply {
            layout(0, 0, width, height)
            treeOwner?.let { setViewTreeLifecycleOwner(it) }
        }

    /**
     * Setting a drawable of another intrinsic size calls requestLayout(), and layout() with the
     * same bounds notifies no listener unless measure() ran first.
     */
    private fun ImageView.relayout(
        width: Int = VIEW_WIDTH,
        height: Int = VIEW_HEIGHT,
    ) {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
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
            downloader.fail(otherUrl, IOException("HTTP 403"))

            loader.load(url, withoutDrawable, placeholder)
            loader.load(otherUrl, withoutResource, android.R.drawable.ic_menu_gallery, 0)
            val resourcePlaceholder = withoutResource.drawable
            withoutDrawable.relayout()
            withoutResource.relayout()
            advanceUntilIdle()

            assertSame(placeholder, withoutDrawable.drawable)
            assertSame(resourcePlaceholder, withoutResource.drawable)
            assertEquals(1, downloader.callCount(url))
            assertEquals(1, downloader.callCount(otherUrl))
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

    private fun TestScope.loadAndSettle(
        loader: ImageLoader,
        view: ImageView,
        imageUrl: String = url,
    ) {
        loader.load(imageUrl, view, placeholder, errorDrawable)
        view.relayout()
        advanceUntilIdle()
    }

    @Test
    fun aSecondLoadOfTheSameImageShowsItFromMemoryAtOnceWithoutAPlaceholder() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, jpegBytes(400, 200, Color.RED))
            loadAndSettle(loader, view)
            val firstBitmap = view.shownBitmap()

            view.relayout()
            loader.load(url, view, placeholder, errorDrawable)

            assertSame(firstBitmap, view.shownBitmap())
            advanceUntilIdle()
            assertEquals(1, downloader.callCount(url))
        }

    @Test
    fun afterFourHoursTheNextLoadShowsThePlaceholderAndDownloadsAgain() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, jpegBytes(400, 200, Color.RED))
            loadAndSettle(loader, view)

            clock.advanceBy(DiskCache.TTL_MILLIS)
            view.relayout()
            loader.load(url, view, placeholder, errorDrawable)
            assertSame(placeholder, view.drawable)
            view.relayout()
            advanceUntilIdle()

            assertEquals(2, downloader.callCount(url))
            assertMostlyRed(view)
        }

    @Test
    fun aRestartedLoaderServesFromDiskWithoutDownloading() =
        runTest {
            downloader.succeed(url, jpegBytes(400, 200, Color.RED))
            loadAndSettle(createLoader(), newImageView())
            val restarted = createLoader(newDiskCache())
            val view = newImageView()

            loadAndSettle(restarted, view)

            assertEquals(1, downloader.callCount(url))
            assertMostlyRed(view)
        }

    @Test
    fun viewsLoadingTheSameUrlShareOneDownloadEvenAtDifferentSizes() =
        runTest {
            val loader = createLoader()
            val large = newImageView()
            val small = newImageView(width = 100, height = 50)
            val gate = downloader.gate(url)

            loader.load(url, large, placeholder, errorDrawable)
            loader.load(url, small, placeholder, errorDrawable)
            large.relayout()
            small.relayout(width = 100, height = 50)
            advanceUntilIdle()
            gate.complete(jpegBytes(1920, 1080, Color.BLUE))
            advanceUntilIdle()

            assertEquals(1, downloader.callCount(url))
            assertMostlyBlue(large)
            assertMostlyBlue(small)
            assertTrue(small.shownBitmap().width < large.shownBitmap().width)
        }

    @Test
    fun nonImageBytesShowTheErrorImageRemoveTheDiskEntryAndAreDownloadedAgain() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, "not an image".toByteArray())

            loadAndSettle(loader, view)

            assertSame(errorDrawable, view.drawable)
            assertNull(diskCache.get(url))
            loadAndSettle(loader, view)
            assertEquals(2, downloader.callCount(url))
        }

    @Test
    fun aDiskEntryThatIsNotAnImageIsRemovedAndTheImageIsDownloaded() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            diskCache.put(url, "not an image".toByteArray(), clock.now)
            downloader.succeed(url, jpegBytes(400, 200, Color.RED))

            loadAndSettle(loader, view)

            assertEquals(1, downloader.callCount(url))
            assertNotSame(errorDrawable, view.drawable)
            assertMostlyRed(view)
        }

    @Test
    fun afterInvalidationALoadShowsThePlaceholderAndDownloadsOnce() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            downloader.succeed(url, jpegBytes(400, 200, Color.RED))
            loadAndSettle(loader, view)
            assertEquals(1, downloader.callCount(url))

            loader.invalidateCache()
            view.relayout()
            loader.load(url, view, placeholder, errorDrawable)
            assertSame(placeholder, view.drawable)
            view.relayout()
            advanceUntilIdle()

            assertEquals(2, downloader.callCount(url))
            assertMostlyRed(view)
        }

    @Test
    fun aDiskSourcedImageInFlightDuringInvalidationIsNeverShownAndTheImageIsDownloaded() =
        runTest {
            val decodeScheduler = TestCoroutineScheduler()
            val loader = createLoader(decodeDispatcher = StandardTestDispatcher(decodeScheduler))
            val view = newImageView()
            diskCache.put(url, jpegBytes(400, 200, Color.RED), clock.now)
            downloader.succeed(url, jpegBytes(400, 200, Color.BLUE))

            // The disk read has happened and decoding is parked on its own scheduler.
            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()
            loader.invalidateCache()
            advanceUntilIdle()
            decodeScheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertSame(placeholder, view.drawable)
            assertEquals(1, downloader.callCount(url))
            decodeScheduler.advanceUntilIdle()
            advanceUntilIdle()
            assertMostlyBlue(view)
        }

    @Test
    fun aDownloadInFlightDuringInvalidationIsShownButNotKeptInMemory() =
        runTest {
            val loader = createLoader()
            val view = newImageView()
            val gate = downloader.gate(url)
            loader.load(url, view, placeholder, errorDrawable)
            view.relayout()
            advanceUntilIdle()

            loader.invalidateCache()
            advanceUntilIdle()
            gate.complete(jpegBytes(400, 200, Color.BLUE))
            advanceUntilIdle()

            assertMostlyBlue(view)
            val otherView = newImageView()
            loader.load(url, otherView, placeholder, errorDrawable)
            assertSame(placeholder, otherView.drawable)
        }

    private companion object {
        const val VIEW_WIDTH = 200
        const val VIEW_HEIGHT = 100
        const val MEMORY_CACHE_BYTES = 32 * 1024 * 1024
    }
}
