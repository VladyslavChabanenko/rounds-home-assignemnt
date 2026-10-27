package io.github.vladyslavchabanenko.imageloader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

internal enum class Source { DISK, NETWORK }

/** The bytes of an image, where they came from, and when they were downloaded. */
internal class FetchResult(
    val bytes: ByteArray,
    val source: Source,
    val downloadedAtMillis: Long,
)

/**
 * Gets image bytes from the disk cache or the network. Concurrent fetches of one URL and cache
 * generation share one download, which runs in [scope] and is cancelled when its last waiter
 * leaves. A failed fetch is never reused, so the next fetch tries again.
 */
internal class ImageFetcher(
    private val downloader: Downloader,
    private val diskCache: DiskCache,
    private val clock: Clock,
    private val diskDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private data class FetchKey(
        val url: String,
        val generation: Int,
    )

    private class SharedFetch(
        val deferred: Deferred<FetchResult>,
    ) {
        var waiters = 0
    }

    private val inFlight = HashMap<FetchKey, SharedFetch>()

    /** [generation] is the loader's cache generation: a fetch of a newer one never joins an older one. */
    suspend fun fetch(
        url: String,
        generation: Int,
    ): FetchResult {
        val key = FetchKey(url, generation)
        val shared =
            synchronized(inFlight) {
                val fetch = inFlight[key] ?: startFetch(key)
                fetch.waiters++
                fetch
            }
        try {
            return shared.deferred.await()
        } finally {
            synchronized(inFlight) {
                shared.waiters--
                if (shared.waiters == 0) {
                    removeIfCurrent(key, shared)
                    shared.deferred.cancel()
                }
            }
        }
    }

    // Callers hold the inFlight lock.
    private fun startFetch(key: FetchKey): SharedFetch {
        val shared = SharedFetch(scope.async { diskOrNetwork(key.url) })
        inFlight[key] = shared
        // A finished or failed fetch leaves the map at once, even if its waiters have not resumed.
        shared.deferred.invokeOnCompletion { synchronized(inFlight) { removeIfCurrent(key, shared) } }
        return shared
    }

    private fun removeIfCurrent(
        key: FetchKey,
        shared: SharedFetch,
    ) {
        if (inFlight[key] === shared) inFlight.remove(key)
    }

    private suspend fun diskOrNetwork(url: String): FetchResult {
        val cached = withContext(diskDispatcher) { diskCache.get(url) }
        if (cached != null) return FetchResult(cached.bytes, Source.DISK, cached.downloadedAtMillis)
        val bytes = downloader.download(url)
        val downloadedAtMillis = clock.nowMillis()
        withContext(diskDispatcher) { diskCache.put(url, bytes, downloadedAtMillis) }
        return FetchResult(bytes, Source.NETWORK, downloadedAtMillis)
    }
}
