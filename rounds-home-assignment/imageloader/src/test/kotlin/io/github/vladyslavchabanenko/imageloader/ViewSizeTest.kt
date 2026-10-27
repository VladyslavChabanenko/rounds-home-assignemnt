@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.vladyslavchabanenko.imageloader

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class ViewSizeTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }.also { activity.setContentView(it) }
    private val image = ImageView(activity)
    private val row =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, IMAGE_HEIGHT))
        }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun addRow(index: Int) {
        list.addView(row, index, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        idleMainLooper()
    }

    @Test
    fun aViewAlreadyLaidOutReturnsItsSizeAtOnce() =
        runTest {
            addRow(0)
            assertTrue(image.isLaidOut)

            val size = image.awaitSize()

            assertEquals(IMAGE_HEIGHT, size.height)
            assertTrue(size.width > 0)
        }

    /**
     * A recycled list row is detached, so it is no longer laid out when it is bound. When the row
     * is attached again at another offset, the image view keeps its bounds inside the row. It is
     * laid out, but layout() notifies none of its layout listeners.
     */
    @Test
    fun aViewInARowAttachedAgainAtAnotherOffsetStillGetsItsSize() =
        runTest(timeout = 10.seconds) {
            addRow(0)
            list.removeView(row)
            assertFalse(image.isLaidOut)

            val size = async(start = CoroutineStart.UNDISPATCHED) { image.awaitSize() }
            list.addView(View(activity), 0, LinearLayout.LayoutParams(1, SPACER_HEIGHT))
            addRow(1)

            assertEquals(IMAGE_HEIGHT, size.await().height)
        }

    private companion object {
        const val IMAGE_HEIGHT = 100
        const val SPACER_HEIGHT = 50
    }
}
