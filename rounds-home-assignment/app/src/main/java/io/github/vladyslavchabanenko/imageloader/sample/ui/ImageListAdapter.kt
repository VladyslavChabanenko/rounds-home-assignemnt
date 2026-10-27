package io.github.vladyslavchabanenko.imageloader.sample.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.vladyslavchabanenko.imageloader.ImageLoader
import io.github.vladyslavchabanenko.imageloader.sample.R
import io.github.vladyslavchabanenko.imageloader.sample.databinding.ItemImageBinding

class ImageListAdapter(
    private val imageLoader: ImageLoader,
) : ListAdapter<ImageListItem, ImageListAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemImageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ImageListItem) {
            val context = binding.root.context
            binding.idLabel.text = context.getString(R.string.image_id_label, item.id)
            binding.image.contentDescription = context.getString(R.string.image_content_description, item.id)
            imageLoader.load(item.imageUrl, binding.image, R.drawable.ic_image_placeholder, R.drawable.ic_broken_image)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ImageListItem>() {
        override fun areItemsTheSame(
            oldItem: ImageListItem,
            newItem: ImageListItem,
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: ImageListItem,
            newItem: ImageListItem,
        ): Boolean = oldItem == newItem

        // A payload lets the item animator rebind the row in place, without a crossfade.
        override fun getChangePayload(
            oldItem: ImageListItem,
            newItem: ImageListItem,
        ): Any? = if (oldItem.copy(cacheGeneration = newItem.cacheGeneration) == newItem) RELOAD else null
    }

    private object RELOAD
}
