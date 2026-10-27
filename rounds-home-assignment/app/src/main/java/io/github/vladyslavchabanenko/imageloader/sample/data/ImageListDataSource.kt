package io.github.vladyslavchabanenko.imageloader.sample.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Provides the image list JSON. The call blocks, so the repository runs it on the IO dispatcher. */
fun interface ImageListDataSource {
    fun fetchImageListJson(): String
}

/** Fetches the image list with an HTTP GET. */
class HttpImageListDataSource(
    private val url: String = IMAGE_LIST_URL,
) : ImageListDataSource {
    override fun fetchImageListJson(): String {
        val connection =
            URL(url).openConnection() as? HttpURLConnection
                ?: throw IOException("Not an HTTP URL: $url")
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            val code = connection.responseCode
            if (code !in HTTP_OK_RANGE) throw IOException("HTTP $code")
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val IMAGE_LIST_URL = "https://zipoapps-storage-test.nyc3.digitaloceanspaces.com/image_list.json"
        private const val TIMEOUT_MILLIS = 30_000
        private val HTTP_OK_RANGE = 200..299
    }
}
