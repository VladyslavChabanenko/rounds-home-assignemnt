package io.github.vladyslavchabanenko.imageloader

/** Wall-clock time. Cached entries outlive the process, so the cache cannot use a monotonic clock. */
internal fun interface Clock {
    fun nowMillis(): Long
}
