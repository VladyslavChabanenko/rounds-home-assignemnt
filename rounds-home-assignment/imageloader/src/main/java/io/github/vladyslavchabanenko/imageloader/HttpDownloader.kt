package io.github.vladyslavchabanenko.imageloader

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the bytes behind a URL. Fails with an [IOException] when the bytes cannot be had. */
internal fun interface Downloader {
    suspend fun download(url: String): ByteArray
}

/**
 * A [Downloader] over [HttpURLConnection]. Both timeouts are in milliseconds. The read timeout is
 * the longest the connection may go without receiving data.
 */
internal class HttpDownloader(
    private val ioDispatcher: CoroutineDispatcher,
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : Downloader {
    override suspend fun download(url: String): ByteArray =
        withContext(ioDispatcher) {
            Log.d(TAG, "download $url")
            val connection =
                URL(url).openConnection() as? HttpURLConnection
                    ?: throw IOException("Not an HTTP URL: $url")
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                val code = connection.responseCode
                if (code !in HTTP_OK_RANGE) throw IOException("HTTP $code")
                connection.inputStream.use { it.readBody(connection.contentLengthLong) }
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun InputStream.readBody(contentLength: Long): ByteArray =
        if (contentLength in 0..Int.MAX_VALUE) {
            readExactly(contentLength.toInt())
        } else {
            readUntilEnd()
        }

    private suspend fun InputStream.readExactly(length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            val read = read(body, offset, minOf(CHUNK_SIZE, length - offset))
            if (read < 0) throw IOException("Stream ended after $offset of $length bytes")
            offset += read
        }
        return body
    }

    private suspend fun InputStream.readUntilEnd(): ByteArray {
        val body = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(chunk)
            if (read < 0) break
            body.write(chunk, 0, read)
        }
        return body.toByteArray()
    }

    private companion object {
        const val TAG = "ImageLoader"
        const val DEFAULT_TIMEOUT_MILLIS = 30_000
        const val CHUNK_SIZE = 8 * 1024
        val HTTP_OK_RANGE = 200..299
    }
}
