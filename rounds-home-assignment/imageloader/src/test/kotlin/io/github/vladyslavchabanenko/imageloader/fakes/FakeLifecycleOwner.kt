package io.github.vladyslavchabanenko.imageloader.fakes

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** A [LifecycleOwner] that tests move through the lifecycle. Create it on the main thread. */
class FakeLifecycleOwner(
    initialState: Lifecycle.State = Lifecycle.State.RESUMED,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = initialState }

    override val lifecycle: Lifecycle get() = registry

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** A context that is also a [LifecycleOwner], standing in for the Activity a view was inflated with. */
class LifecycleContext(
    base: Context,
    owner: LifecycleOwner,
) : ContextWrapper(base),
    LifecycleOwner by owner
