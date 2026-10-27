package io.github.vladyslavchabanenko.imageloader.sample.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.github.vladyslavchabanenko.imageloader.ImageLoader
import io.github.vladyslavchabanenko.imageloader.sample.R
import io.github.vladyslavchabanenko.imageloader.sample.databinding.ActivityImageListBinding
import kotlinx.coroutines.launch

class ImageListActivity : AppCompatActivity() {
    private val viewModel: ImageListViewModel by viewModels { ImageListViewModel.Factory }

    private lateinit var binding: ActivityImageListBinding
    private lateinit var adapter: ImageListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityImageListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter =
            ImageListAdapter(ImageLoader.get(this)).apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
        binding.list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        // In landscape the navigation bar sits beside the screen, over the toolbar's action.
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, right = bars.right)
            insets
        }
        binding.retryButton.setOnClickListener { viewModel.onRetry() }
        binding.toolbar.inflateMenu(R.menu.image_list)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_invalidate_cache) {
                viewModel.onInvalidateCacheClicked()
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handle) }
            }
        }
    }

    private fun handle(event: ImageListEvent) {
        when (event) {
            ImageListEvent.CacheInvalidated -> Snackbar.make(binding.root, R.string.cache_invalidated, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun render(state: ImageListUiState) {
        binding.progress.isVisible = state is ImageListUiState.Loading
        binding.list.isVisible = state is ImageListUiState.Content
        binding.errorGroup.isVisible = state is ImageListUiState.Error
        if (state is ImageListUiState.Content) adapter.submitList(state.items)
    }
}
