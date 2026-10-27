package io.github.vladyslavchabanenko.imageloader

import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.CancellableContinuation
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
 * Returns the laid-out size, or suspends until the view has one. Every listener is removed when
 * the wait ends or the caller is cancelled.
 */
internal suspend fun View.awaitSize(): PixelSize {
    laidOutSizeOrNull()?.let { return it }
    return suspendCancellableCoroutine { continuation ->
        val waiter = SizeWaiter(this, continuation)
        waiter.start()
        continuation.invokeOnCancellation { waiter.stop() }
    }
}

/**
 * Resumes [continuation] once [view] has a size. A layout pass that changes the view's bounds
 * notifies the layout listener. A view that is attached again with unchanged bounds, such as a
 * recycled list row, is laid out without any notification, so the next draw resumes the wait too.
 */
private class SizeWaiter(
    private val view: View,
    private val continuation: CancellableContinuation<PixelSize>,
) : View.OnLayoutChangeListener,
    View.OnAttachStateChangeListener,
    ViewTreeObserver.OnPreDrawListener {
    private var observer = view.viewTreeObserver

    fun start() {
        view.addOnLayoutChangeListener(this)
        view.addOnAttachStateChangeListener(this)
        observer.addOnPreDrawListener(this)
    }

    fun stop() {
        view.removeOnLayoutChangeListener(this)
        view.removeOnAttachStateChangeListener(this)
        removePreDrawListener(observer)
        removePreDrawListener(view.viewTreeObserver)
    }

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
        // isLaidOut is set only after the listeners run, so the size comes from the arguments.
        finish(view.contentSize(right - left, bottom - top))
    }

    override fun onPreDraw(): Boolean {
        view.laidOutSizeOrNull()?.let(::finish)
        return true
    }

    // Attaching moves this listener from the view's own observer into the window's.
    override fun onViewAttachedToWindow(v: View) {
        observer = v.viewTreeObserver
    }

    override fun onViewDetachedFromWindow(v: View) = Unit

    private fun finish(size: PixelSize) {
        stop()
        if (continuation.isActive) continuation.resume(size)
    }

    private fun removePreDrawListener(from: ViewTreeObserver) {
        if (from.isAlive) from.removeOnPreDrawListener(this)
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
