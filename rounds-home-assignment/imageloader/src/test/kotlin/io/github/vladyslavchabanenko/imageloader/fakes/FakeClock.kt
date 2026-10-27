package io.github.vladyslavchabanenko.imageloader.fakes

import io.github.vladyslavchabanenko.imageloader.Clock

/** A [Clock] that only moves when a test says so. */
internal class FakeClock(
    var now: Long = 1_000_000L,
) : Clock {
    override fun nowMillis(): Long = now

    fun advanceBy(millis: Long) {
        now += millis
    }
}
