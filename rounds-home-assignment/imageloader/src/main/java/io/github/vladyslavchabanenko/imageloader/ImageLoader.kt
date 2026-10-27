package io.github.vladyslavchabanenko.imageloader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import androidx.annotation.AnyThread
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads images into [ImageView]s, with a memory cache and a disk cache valid for 4 hours.
 * Obtain the process-wide instance with [get].
 */
public class ImageLoader private constructor(
    downloader: Downloader,
    private val diskCache: DiskCache,
    private val clock: Clock,
    memoryCacheMaxBytes: Int,
    private val mainDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
) {
    private val libraryScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    // Counts invalidations. A request that started before one never reuses cached data after it.
    private val generation = AtomicInteger(0)

    // One thread at a time, in order, so DiskCache needs no locks and a clear() runs before later reads.
    private val diskDispatcher = ioDispatcher.limitedParallelism(1)
    private val fetcher = ImageFetcher(downloader, diskCache, clock, diskDispatcher, libraryScope)

    private val memoryCache =
        object : LruCache<MemoryKey, MemoryEntry>(memoryCacheMaxBytes) {
            override fun sizeOf(
                key: MemoryKey,
                value: MemoryEntry,
            ): Int = value.bitmap.allocationByteCount
        }

    /**
     * Shows [placeholderResId] in [target] immediately, then the image at [url] once it is ready.
     * On failure shows [errorResId], or keeps the placeholder when [errorResId] is 0.
     */
    @MainThread
    @JvmOverloads
    public fun load(
        url: String,
        target: ImageView,
        @DrawableRes placeholderResId: Int,
        @DrawableRes errorResId: Int = 0,
    ) {
        val placeholder =
            requireNotNull(target.context.getDrawable(placeholderResId)) {
                "Resource $placeholderResId is not a drawable"
            }
        val error = if (errorResId == 0) null else target.context.getDrawable(errorResId)
        load(url, target, placeholder, error)
    }

    /** Same as the resource overload, with ready-made drawables. [error] `null` keeps the placeholder. */
    @MainThread
    @JvmOverloads
    public fun load(
        url: String,
        target: ImageView,
        placeholder: Drawable,
        error: Drawable? = null,
    ) {
        (target.getTag(R.id.imageloader_request) as? Job)?.cancel()
        val requestGeneration = generation.get()

        val cached = target.laidOutSizeOrNull()?.let { lookUpMemory(MemoryKey(url, it.width, it.height)) }
        if (cached != null) {
            target.setImageBitmap(cached.bitmap)
            target.setTag(R.id.imageloader_request, null)
            return
        }
        target.setImageDrawable(placeholder)

        val owner = target.findViewTreeLifecycleOwner() ?: target.context.findLifecycleOwner()
        val scope = owner?.lifecycleScope ?: libraryScope
        val job =
            scope.launch(mainDispatcher, start = CoroutineStart.LAZY) {
                val self = coroutineContext.job
                val isCurrent = { target.getTag(R.id.imageloader_request) === self }
                var treeOwnerWatch: DisposableHandle? = null
                try {
                    val size = target.awaitSize()
                    treeOwnerWatch = cancelWhenTreeOwnerDestroyed(target, owner, self)
                    val bitmap = loadBitmap(url, size, requestGeneration)
                    if (isCurrent()) target.setImageBitmap(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load $url", e)
                    if (error != null && isCurrent()) target.setImageDrawable(error)
                } finally {
                    treeOwnerWatch?.dispose()
                }
            }
        target.setTag(R.id.imageloader_request, job)
        job.start()
    }

    /** Discards every cached image (memory and disk). Subsequent loads download again. */
    @AnyThread
    public fun invalidateCache() {
        synchronized(memoryCache) {
            generation.incrementAndGet()
            memoryCache.evictAll()
        }
        libraryScope.launch(diskDispatcher) { diskCache.clear() }
    }

    /**
     * The bitmap from memory, else decoded from the fetched bytes. A result from disk is not used when the
     * cache was invalidated meanwhile, and bytes from disk that are not an image are fetched once more.
     */
    private suspend fun loadBitmap(
        url: String,
        size: PixelSize,
        startGeneration: Int,
    ): Bitmap {
        val key = MemoryKey(url, size.width, size.height)
        lookUpMemory(key)?.let { return it.bitmap }
        var requestGeneration = startGeneration
        var retried = false
        while (true) {
            val result = fetcher.fetch(url, requestGeneration)
            try {
                val bitmap = withContext(defaultDispatcher) { BitmapDecoder.decode(result.bytes, size.width, size.height) }
                if (result.source == Source.DISK && requestGeneration != generation.get()) {
                    requestGeneration = generation.get()
                    continue
                }
                putInMemory(key, MemoryEntry(bitmap, result.downloadedAtMillis), requestGeneration)
                return bitmap
            } catch (e: InvalidImageException) {
                // Queued on the serialized disk dispatcher before the next fetch reads the disk.
                libraryScope.launch(diskDispatcher) { diskCache.remove(url) }
                if (result.source != Source.DISK || retried) throw e
                retried = true
            }
        }
    }

    // Under the same lock as invalidateCache(), so an invalidation never leaves an older entry behind.
    private fun putInMemory(
        key: MemoryKey,
        entry: MemoryEntry,
        requestGeneration: Int,
    ) {
        synchronized(memoryCache) {
            if (requestGeneration == generation.get()) memoryCache.put(key, entry)
        }
    }

    private fun lookUpMemory(key: MemoryKey): MemoryEntry? {
        val entry = memoryCache.get(key) ?: return null
        if (DiskCache.isFresh(entry.downloadedAtMillis, clock.nowMillis())) return entry
        memoryCache.remove(key)
        return null
    }

    /**
     * Once the view is laid out it normally has a view-tree owner. When that owner is not the one
     * whose scope runs the request, the request also ends when the view-tree owner is destroyed.
     */
    private fun cancelWhenTreeOwnerDestroyed(
        target: ImageView,
        scopeOwner: LifecycleOwner?,
        request: Job,
    ): DisposableHandle? {
        val treeOwner = target.findViewTreeLifecycleOwner()
        if (treeOwner == null || treeOwner === scopeOwner) return null
        val treeOwnerScopeJob = treeOwner.lifecycleScope.coroutineContext.job
        return treeOwnerScopeJob.invokeOnCompletion { request.cancel() }
    }

    private fun Context.findLifecycleOwner(): LifecycleOwner? {
        var current: Context? = this
        while (current != null) {
            if (current is LifecycleOwner) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** Access to the process-wide instance. */
    public companion object {
        private const val TAG = "ImageLoader"
        private const val CACHE_DIRECTORY = "image_loader"

        @Volatile
        private var instance: ImageLoader? = null

        /** Returns the process-wide [ImageLoader], creating it on first use. */
        @JvmStatic
        public fun get(context: Context): ImageLoader =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val clock = Clock { System.currentTimeMillis() }
                    val diskCache = DiskCache(lazy { File(appContext.cacheDir, CACHE_DIRECTORY) }, clock)
                    val memoryCacheBytes = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    create(
                        HttpDownloader(Dispatchers.IO),
                        diskCache,
                        clock,
                        memoryCacheBytes,
                        Dispatchers.Main,
                        Dispatchers.IO,
                        Dispatchers.Default,
                    )
                }.also { instance = it }
            }

        // The constructor is private, so Java callers see no way to build a loader from internal types.
        internal fun create(
            downloader: Downloader,
            diskCache: DiskCache,
            clock: Clock,
            memoryCacheMaxBytes: Int,
            mainDispatcher: CoroutineDispatcher,
            ioDispatcher: CoroutineDispatcher,
            defaultDispatcher: CoroutineDispatcher,
        ): ImageLoader = ImageLoader(downloader, diskCache, clock, memoryCacheMaxBytes, mainDispatcher, ioDispatcher, defaultDispatcher)
    }
}

/** A decoded bitmap is cached per URL and per the size of the view it was decoded for. */
internal data class MemoryKey(
    val url: String,
    val width: Int,
    val height: Int,
)

/** A decoded bitmap and the download time of the bytes it came from, which ends its validity. */
internal class MemoryEntry(
    val bitmap: Bitmap,
    val downloadedAtMillis: Long,
)
