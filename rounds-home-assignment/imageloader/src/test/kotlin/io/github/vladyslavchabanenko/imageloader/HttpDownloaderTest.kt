package io.github.vladyslavchabanenko.imageloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpDownloaderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun downloader(readTimeoutMillis: Int = 10_000) =
        HttpDownloader(
            Dispatchers.IO,
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = readTimeoutMillis,
        )

    private fun url() = server.url("/image.jpg").toString()

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun responseWithContentLengthReturnsExactBytes() =
        runTest {
            val expected = bytes(50_000)
            server.enqueue(MockResponse.Builder().body(Buffer().write(expected)).build())

            assertArrayEquals(expected, downloader().download(url()))
        }

    @Test
    fun chunkedResponseWithoutContentLengthReturnsExactBytes() =
        runTest {
            val expected = bytes(50_000)
            server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(expected), 4_096).build())

            assertArrayEquals(expected, downloader().download(url()))
        }

    @Test
    fun forbiddenResponseThrowsIOException() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .body("<Error>Forbidden</Error>")
                    .build(),
            )

            val failure = runCatching { downloader().download(url()) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("HTTP 403", failure?.message)
        }

    @Test
    fun serverErrorResponseThrowsIOException() =
        runTest {
            server.enqueue(MockResponse.Builder().code(500).build())

            val failure = runCatching { downloader().download(url()) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("HTTP 500", failure?.message)
        }

    @Test
    fun stalledBodyThrowsIOExceptionAfterReadTimeout() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(Buffer().write(bytes(2_000)))
                    .throttleBody(1_000, 2, TimeUnit.SECONDS)
                    .build(),
            )

            val failure = runCatching { downloader(readTimeoutMillis = 200).download(url()) }.exceptionOrNull()

            assertTrue(failure is IOException)
        }

    @Test
    fun cancellingTheCallerStopsReadingTheBody() =
        runTest {
            // 20 000 bytes at 100 bytes per 50 ms: the body alone would take 10 s.
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(Buffer().write(bytes(20_000)))
                    .throttleBody(100, 50, TimeUnit.MILLISECONDS)
                    .build(),
            )

            val outcome =
                withContext(Dispatchers.Default) {
                    val job = launch { downloader().download(url()) }
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    delay(200)
                    val startNanos = System.nanoTime()
                    job.cancelAndJoin()
                    job.isCancelled to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                }

            assertTrue(outcome.first)
            assertTrue("cancel took ${outcome.second} ms", outcome.second < 2_000)
        }
}
