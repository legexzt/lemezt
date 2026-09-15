package com.lemezt.app.feature.favorites

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lemezt.app.R
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.core.util.ImageLoader
import com.lemezt.app.databinding.ItemDrawerFavoriteBinding

class FavoritesDrawerAdapter(
    private val onItemClick: (DownloadEntity) -> Unit
) : RecyclerView.Adapter<FavoritesDrawerAdapter.ViewHolder>() {

    private val items = mutableListOf<DownloadEntity>()

    fun submitList(newItems: List<DownloadEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDrawerFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDrawerFavoriteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadEntity) {
            binding.tvFavTitle.text = item.title
            val isVideo = item.formatType == "VIDEO" || item.container.lowercase() == "mp4"
            val typeStr = if (isVideo) "Video" else "Audio"
            binding.tvFavSubtitle.text = "${item.author ?: "Unknown"} • $typeStr"

            if (isVideo) {
                binding.ivFavTypeIcon.setImageResource(R.drawable.ic_play)
            } else {
                binding.ivFavTypeIcon.setImageResource(R.drawable.ic_audio)
            }

            if (!item.thumbnailUrl.isNullOrEmpty()) {
                ImageLoader.load(item.thumbnailUrl, binding.ivFavThumbnail, cornerRadiusDp = 8f)
            } else {
                binding.ivFavThumbnail.setImageResource(R.drawable.ic_download)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}

