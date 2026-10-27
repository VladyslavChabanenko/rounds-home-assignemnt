package io.github.vladyslavchabanenko.imageloader

import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A positive width and height in pixels. */
internal data class PixelSize(
    val width: Int,
    val height: Int,
)

/**
 * The size this view has been laid out at, without its padding, or `null` while it has no
 * current layout. A dimension that is not positive is replaced by the display's.
 */
internal fun View.laidOutSizeOrNull(): PixelSize? = if (isLaidOut && !isLayoutRequested) contentSize(width, height) else null

/**
 * Returns the laid-out size, or suspends until the next layout pass of this view. The layout
 * listener is removed when the wait ends or the caller is cancelled.
 */
internal suspend fun View.awaitSize(): PixelSize {
    laidOutSizeOrNull()?.let { return it }
    return suspendCancellableCoroutine { continuation ->
        val listener =
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    v.removeOnLayoutChangeListener(this)
                    // isLaidOut is set only after the listeners run, so the size comes from the arguments.
                    continuation.resume(contentSize(right - left, bottom - top))
                }
            }
        addOnLayoutChangeListener(listener)
        continuation.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
    }
}

private fun View.contentSize(
    viewWidth: Int,
    viewHeight: Int,
): PixelSize {
    val contentWidth = viewWidth - paddingLeft - paddingRight
    val contentHeight = viewHeight - paddingTop - paddingBottom
    val metrics = resources.displayMetrics
    return PixelSize(
        width = if (contentWidth > 0) contentWidth else metrics.widthPixels,
        height = if (contentHeight > 0) contentHeight else metrics.heightPixels,
    )
}
