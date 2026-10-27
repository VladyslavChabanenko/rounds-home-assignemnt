package io.github.vladyslavchabanenko.imageloader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.ImageView
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

/**
 * Downloads images into [ImageView]s, with a memory cache and a disk cache valid for 4 hours.
 * Obtain the process-wide instance with [get].
 */
public class ImageLoader internal constructor(
    private val downloader: Downloader,
    private val mainDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
) {
    private val libraryScope = CoroutineScope(SupervisorJob() + mainDispatcher)

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
                    val bytes = downloader.download(url)
                    val bitmap = withContext(defaultDispatcher) { BitmapDecoder.decode(bytes, size.width, size.height) }
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

        @Volatile
        private var instance: ImageLoader? = null

        /** Returns the process-wide [ImageLoader], creating it on first use. */
        @JvmStatic
        public fun get(context: Context): ImageLoader =
            instance ?: synchronized(this) {
                instance ?: ImageLoader(
                    HttpDownloader(Dispatchers.IO),
                    Dispatchers.Main,
                    Dispatchers.Default,
                ).also { instance = it }
            }
    }
}
