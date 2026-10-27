package io.github.vladyslavchabanenko.imageloader

import io.github.vladyslavchabanenko.imageloader.fakes.FakeClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class DiskCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val clock = FakeClock()
    private val url = "https://example.test/a.jpg"
    private val payload = ByteArray(100) { it.toByte() }

    private val directory by lazy { temporaryFolder.newFolder("image_loader") }

    private fun cache(
        maxBytes: Long = 10_000L,
        dir: File = directory,
    ) = DiskCache(lazyOf(dir), clock, maxBytes)

    private fun fileNameFor(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".img"

    private fun header(
        magic: String = "ILC1",
        downloadedAt: Long = clock.now,
    ): ByteArray = magic.toByteArray() + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(downloadedAt).array()

    private fun filesInDirectory(): List<String> = directory.list().orEmpty().sorted()

    @Test
    fun isFreshHoldsFromTheDownloadUntilJustBeforeFourHours() {
        val downloadedAt = 5_000L
        val fourHours = 4 * 60 * 60 * 1000L

        assertTrue(DiskCache.isFresh(downloadedAt, downloadedAt))
        assertTrue(DiskCache.isFresh(downloadedAt, downloadedAt + fourHours - 1))
        assertFalse(DiskCache.isFresh(downloadedAt, downloadedAt + fourHours))
        assertFalse(DiskCache.isFresh(downloadedAt, downloadedAt - 1))
    }

    @Test
    fun getReturnsTheBytesAndDownloadTimeWhileFresh() {
        val cache = cache()
        cache.put(url, payload, clock.now)
        clock.advanceBy(DiskCache.TTL_MILLIS - 1)

        val entry = cache.get(url)

        assertNotNull(entry)
        assertArrayEquals(payload, entry?.bytes)
        assertEquals(1_000_000L, entry?.downloadedAtMillis)
    }

    @Test
    fun anEntryExactlyFourHoursOldIsExpiredAndItsFileIsDeleted() {
        val cache = cache()
        cache.put(url, payload, clock.now)
        clock.advanceBy(DiskCache.TTL_MILLIS)

        assertNull(cache.get(url))
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test
    fun anEntryDownloadedInTheFutureIsExpiredAndItsFileIsDeleted() {
        val cache = cache()
        cache.put(url, payload, clock.now)
        clock.advanceBy(-1)

        assertNull(cache.get(url))
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test
    fun aNewInstanceOnTheSameDirectoryFindsTheEntry() {
        cache().put(url, payload, clock.now)

        val entry = cache().get(url)

        assertArrayEquals(payload, entry?.bytes)
    }

    @Test
    fun aFileWithBadMagicIsAbsentAndDeleted() {
        File(directory, fileNameFor(url)).writeBytes(header(magic = "XXXX") + payload)

        assertNull(cache().get(url))
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test
    fun aFileShorterThanTheHeaderIsAbsentAndDeleted() {
        File(directory, fileNameFor(url)).writeBytes(byteArrayOf(1, 2, 3))

        assertNull(cache().get(url))
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test
    fun aHeaderWithAnEmptyPayloadIsAbsentAndDeleted() {
        File(directory, fileNameFor(url)).writeBytes(header())

        assertNull(cache().get(url))
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test
    fun aLeftoverTempFileIsDeletedWhenTheIndexIsBuiltAndIsNeverReturned() {
        val temp = File(directory, fileNameFor(url) + ".tmp")
        temp.writeBytes(header() + payload)

        assertNull(cache().get(url))
        assertFalse(temp.exists())
    }

    @Test
    fun putsBeyondTheLimitEvictTheLeastRecentlyUsedEntries() {
        val cache = cache(maxBytes = 300)
        val (a, b, c) = listOf("https://example.test/a", "https://example.test/b", "https://example.test/c")

        cache.put(a, payload, clock.now)
        cache.put(b, payload, clock.now)
        cache.put(c, payload, clock.now)

        assertNull(cache.get(a))
        assertNotNull(cache.get(b))
        assertNotNull(cache.get(c))
    }

    @Test
    fun aGetRefreshesRecencySoTheOtherEntryIsEvicted() {
        val cache = cache(maxBytes = 300)
        val (a, b, c) = listOf("https://example.test/a", "https://example.test/b", "https://example.test/c")
        cache.put(a, payload, clock.now)
        cache.put(b, payload, clock.now)
        cache.get(a)

        cache.put(c, payload, clock.now)

        assertNotNull(cache.get(a))
        assertNull(cache.get(b))
        assertNotNull(cache.get(c))
    }

    @Test
    fun anEntryLargerThanTheLimitIsNotStored() {
        val cache = cache(maxBytes = 300)

        cache.put(url, ByteArray(400), clock.now)

        assertNull(cache.get(url))
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test
    fun removeDeletesOnlyThatEntry() {
        val cache = cache()
        val other = "https://example.test/other"
        cache.put(url, payload, clock.now)
        cache.put(other, payload, clock.now)

        cache.remove(url)

        assertNull(cache.get(url))
        assertNotNull(cache.get(other))
    }

    @Test
    fun clearDeletesEveryFile() {
        val cache = cache()
        cache.put(url, payload, clock.now)
        cache.put("https://example.test/other", payload, clock.now)

        cache.clear()

        assertTrue(filesInDirectory().isEmpty())
        assertNull(cache.get(url))
    }

    @Test
    fun aFailingWriteDoesNotThrowAndLeavesNoEntry() {
        val regularFile = temporaryFolder.newFile("not-a-directory")
        val cache = cache(dir = File(regularFile, "image_loader"))

        cache.put(url, payload, clock.now)

        assertNull(cache.get(url))
    }
}
