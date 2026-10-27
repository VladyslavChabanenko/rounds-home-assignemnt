package io.github.vladyslavchabanenko.imageloader.fakes

import io.github.vladyslavchabanenko.imageloader.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A [Downloader] whose responses are scripted per URL. An unscripted URL fails with [IOException]. */
internal class FakeDownloader : Downloader {
    private val responses = ConcurrentHashMap<String, suspend () -> ByteArray>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private val cancellations = ConcurrentHashMap<String, AtomicInteger>()

    fun succeed(
        url: String,
        bytes: ByteArray,
    ) {
        responses[url] = { bytes }
    }

    fun fail(
        url: String,
        exception: Exception,
    ) {
        responses[url] = { throw exception }
    }

    /** Keeps calls for [url] suspended until the returned deferred is completed. */
    fun gate(url: String): CompletableDeferred<ByteArray> {
        val deferred = CompletableDeferred<ByteArray>()
        responses[url] = { deferred.await() }
        return deferred
    }

    fun callCount(url: String): Int = calls[url]?.get() ?: 0

    /** Calls for [url] that ended with a [CancellationException]. */
    fun cancelledCount(url: String): Int = cancellations[url]?.get() ?: 0

    override suspend fun download(url: String): ByteArray {
        calls.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
        val response = responses[url] ?: throw IOException("No response scripted for $url")
        try {
            return response()
        } catch (e: CancellationException) {
            cancellations.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
            throw e
        }
    }
}
