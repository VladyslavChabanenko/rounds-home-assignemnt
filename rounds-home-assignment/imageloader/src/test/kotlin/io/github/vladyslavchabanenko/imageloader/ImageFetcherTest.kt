@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.vladyslavchabanenko.imageloader

import io.github.vladyslavchabanenko.imageloader.fakes.FakeClock
import io.github.vladyslavchabanenko.imageloader.fakes.FakeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImageFetcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val clock = FakeClock()
    private val downloader = FakeDownloader()
    private val url = "https://example.test/a.jpg"
    private val bytes = ByteArray(64) { it.toByte() }

    private val diskCache by lazy { DiskCache(lazy { temporaryFolder.root }, clock) }

    // Like the library scope: a SupervisorJob, so a failed download reaches its waiters and nothing else.
    // It is not backgroundScope, whose work advanceUntilIdle() leaves unrun.
    private fun TestScope.createFetcher(cache: DiskCache = diskCache): ImageFetcher {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ImageFetcher(downloader, cache, clock, dispatcher, CoroutineScope(SupervisorJob() + dispatcher))
    }

    @Test
    fun twoConcurrentFetchesShareOneDownload() =
        runTest {
            val fetcher = createFetcher()
            val gate = downloader.gate(url)

            val first = async { fetcher.fetch(url, 0) }
            val second = async { fetcher.fetch(url, 0) }
            advanceUntilIdle()
            gate.complete(bytes)
            advanceUntilIdle()

            assertEquals(1, downloader.callCount(url))
            assertArrayEquals(bytes, first.await().bytes)
            assertArrayEquals(bytes, second.await().bytes)
            assertEquals(Source.NETWORK, first.await().source)
        }

    @Test
    fun aFreshDiskEntryIsServedWithItsDownloadTimeAndWithoutDownloading() =
        runTest {
            val fetcher = createFetcher()
            diskCache.put(url, bytes, clock.now)
            clock.advanceBy(60_000)

            val result = fetcher.fetch(url, 0)

            assertEquals(Source.DISK, result.source)
            assertEquals(1_000_000L, result.downloadedAtMillis)
            assertArrayEquals(bytes, result.bytes)
            assertEquals(0, downloader.callCount(url))
        }

    @Test
    fun aMissDownloadsOnceAndWritesTheBytesWithTheDownloadTime() =
        runTest {
            val fetcher = createFetcher()
            downloader.succeed(url, bytes)

            val result = fetcher.fetch(url, 0)

            assertEquals(Source.NETWORK, result.source)
            assertEquals(clock.now, result.downloadedAtMillis)
            assertEquals(1, downloader.callCount(url))
            val stored = diskCache.get(url)
            assertArrayEquals(bytes, stored?.bytes)
            assertEquals(clock.now, stored?.downloadedAtMillis)
        }

    @Test
    fun aFailedDownloadThrowsToEveryWaiterWritesNothingAndIsRetriedByTheNextFetch() =
        runTest {
            val fetcher = createFetcher()
            val gate = downloader.gate(url)
            val first = async { runCatching { fetcher.fetch(url, 0) } }
            val second = async { runCatching { fetcher.fetch(url, 0) } }
            advanceUntilIdle()

            gate.completeExceptionally(IOException("HTTP 403"))
            advanceUntilIdle()

            assertTrue(first.await().exceptionOrNull() is IOException)
            assertTrue(second.await().exceptionOrNull() is IOException)
            assertNull(diskCache.get(url))
            downloader.succeed(url, bytes)
            assertArrayEquals(bytes, fetcher.fetch(url, 0).bytes)
            assertEquals(2, downloader.callCount(url))
        }

    @Test
    fun cancellingTheOnlyWaiterCancelsTheDownload() =
        runTest {
            val fetcher = createFetcher()
            downloader.gate(url)
            val waiter = launch { fetcher.fetch(url, 0) }
            advanceUntilIdle()

            waiter.cancel()
            advanceUntilIdle()

            assertEquals(1, downloader.cancelledCount(url))
        }

    @Test
    fun cancellingOneOfTwoWaitersKeepsTheDownloadForTheOther() =
        runTest {
            val fetcher = createFetcher()
            val gate = downloader.gate(url)
            val cancelled = launch { fetcher.fetch(url, 0) }
            val remaining = async { fetcher.fetch(url, 0) }
            advanceUntilIdle()

            cancelled.cancel()
            advanceUntilIdle()
            gate.complete(bytes)
            advanceUntilIdle()

            assertEquals(0, downloader.cancelledCount(url))
            assertEquals(1, downloader.callCount(url))
            assertArrayEquals(bytes, remaining.await().bytes)
        }

    @Test
    fun aFailingDiskWriteStillReturnsTheBytes() =
        runTest {
            val regularFile = temporaryFolder.newFile("not-a-directory")
            val unwritable = DiskCache(lazyOf(File(regularFile, "image_loader")), clock)
            val fetcher = createFetcher(unwritable)
            downloader.succeed(url, bytes)

            val result = fetcher.fetch(url, 0)

            assertArrayEquals(bytes, result.bytes)
            assertNull(unwritable.get(url))
        }

    @Test
    fun aNewFetchDownloadsAgainEvenWhileAWaiterOfTheFailedFetchHasNotResumed() =
        runTest {
            val fetcher = createFetcher()
            val gate = downloader.gate(url)
            // Its dispatcher is never advanced, so this waiter stays suspended after the fetch fails.
            // It is outside the test scope, which would otherwise wait for it to finish.
            val pausedScope = CoroutineScope(StandardTestDispatcher())
            val stuckWaiter = pausedScope.launch(start = CoroutineStart.UNDISPATCHED) { fetcher.fetch(url, 0) }
            advanceUntilIdle()

            gate.completeExceptionally(IOException("HTTP 500"))
            advanceUntilIdle()
            downloader.succeed(url, bytes)
            val result = fetcher.fetch(url, 0)

            assertFalse(stuckWaiter.isCompleted)
            assertArrayEquals(bytes, result.bytes)
            assertEquals(2, downloader.callCount(url))
            pausedScope.cancel()
        }

    @Test
    fun aFetchOfANewGenerationStartsItsOwnDownloadWhileTheOldOneIsInFlight() =
        runTest {
            val fetcher = createFetcher()
            val gate = downloader.gate(url)
            val oldGeneration = async { fetcher.fetch(url, 0) }
            advanceUntilIdle()
            assertEquals(1, downloader.callCount(url))

            val newGeneration = async { fetcher.fetch(url, 1) }
            advanceUntilIdle()
            gate.complete(bytes)
            advanceUntilIdle()

            assertEquals(2, downloader.callCount(url))
            assertArrayEquals(bytes, oldGeneration.await().bytes)
            assertArrayEquals(bytes, newGeneration.await().bytes)
        }

    @Test
    fun concurrentFetchesOfTheSameNewGenerationShareOneDownload() =
        runTest {
            val fetcher = createFetcher()
            val gate = downloader.gate(url)

            val first = async { fetcher.fetch(url, 1) }
            val second = async { fetcher.fetch(url, 1) }
            advanceUntilIdle()
            gate.complete(bytes)
            advanceUntilIdle()

            assertEquals(1, downloader.callCount(url))
            assertArrayEquals(bytes, first.await().bytes)
            assertArrayEquals(bytes, second.await().bytes)
        }
}
