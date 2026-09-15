package com.lemezt.app.feature.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lemezt.app.R
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.core.util.ImageLoader
import com.lemezt.app.databinding.ItemDownloadRowBinding

class DownloadsAdapter(
    private val onPauseResumeClick: (DownloadEntity) -> Unit,
    private val onCancelDeleteClick: (DownloadEntity) -> Unit,
    private val onItemClick: (DownloadEntity) -> Unit
) : ListAdapter<DownloadEntity, DownloadsAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemDownloadRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.tvRowTitle.text = item.title
        if (!item.thumbnailUrl.isNullOrEmpty()) {
            ImageLoader.load(item.thumbnailUrl, b.ivRowThumb, cornerRadiusDp = 8f)
        } else {
            b.ivRowThumb.setImageResource(R.drawable.ic_download)
        }
        b.tvRowFormat.text = "${item.container.uppercase()} • ${item.resolution}"

        when (item.status) {
            "DOWNLOADING" -> {
                b.tvRowStatusBadge.text = "${item.progressPercent}%"
                b.pbRowProgress.visibility = View.VISIBLE
                b.pbRowProgress.progress = item.progressPercent
                val speed = if (item.speedBytesPerSec > 0) String.format("%.1f MB/s", item.speedBytesPerSec / (1024.0 * 1024.0)) else ""
                b.tvRowProgressText.text = "Downloading ($speed)"
                b.btnRowPauseResume.setImageResource(R.drawable.ic_pause)
                b.btnRowPauseResume.visibility = View.VISIBLE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_close)
            }
            "RESOLVING" -> {
                b.tvRowStatusBadge.text = "Resolving"
                b.pbRowProgress.visibility = View.VISIBLE
                b.pbRowProgress.isIndeterminate = true
                b.tvRowProgressText.text = "Resolving stream..."
                b.btnRowPauseResume.visibility = View.GONE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_close)
            }
            "PROCESSING" -> {
                b.tvRowStatusBadge.text = "Muxing"
                b.pbRowProgress.visibility = View.VISIBLE
                b.pbRowProgress.isIndeterminate = true
                b.tvRowProgressText.text = "Processing audio & video..."
                b.btnRowPauseResume.visibility = View.GONE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_close)
            }
            "SAVING" -> {
                b.tvRowStatusBadge.text = "Saving"
                b.pbRowProgress.visibility = View.VISIBLE
                b.pbRowProgress.isIndeterminate = true
                b.tvRowProgressText.text = "Publishing to storage..."
                b.btnRowPauseResume.visibility = View.GONE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_close)
            }
            "PAUSED_BY_USER", "WAITING_FOR_NETWORK" -> {
                b.tvRowStatusBadge.text = "Paused"
                b.pbRowProgress.visibility = View.VISIBLE
                b.pbRowProgress.progress = item.progressPercent
                b.tvRowProgressText.text = item.pauseReason ?: "Paused"
                b.btnRowPauseResume.setImageResource(R.drawable.ic_play)
                b.btnRowPauseResume.visibility = View.VISIBLE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_close)
            }
            "COMPLETED" -> {
                b.tvRowStatusBadge.text = "Completed"
                b.pbRowProgress.visibility = View.GONE
                b.tvRowProgressText.text = "Saved to storage"
                b.btnRowPauseResume.visibility = View.GONE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_delete)
            }
            "FAILED" -> {
                b.tvRowStatusBadge.text = "Failed"
                b.pbRowProgress.visibility = View.GONE
                b.tvRowProgressText.text = item.errorMessage ?: "Failed"
                b.btnRowPauseResume.setImageResource(R.drawable.ic_play)
                b.btnRowPauseResume.visibility = View.VISIBLE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_delete)
            }
            else -> {
                b.tvRowStatusBadge.text = item.status
                b.pbRowProgress.visibility = View.GONE
                b.tvRowProgressText.text = "Queued"
                b.btnRowPauseResume.visibility = View.GONE
                b.btnRowCancelDelete.setImageResource(R.drawable.ic_close)
            }
        }

        b.btnRowPauseResume.setOnClickListener { onPauseResumeClick(item) }
        b.btnRowCancelDelete.setOnClickListener { onCancelDeleteClick(item) }
        b.root.setOnClickListener { onItemClick(item) }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DownloadEntity>() {
        override fun areItemsTheSame(oldItem: DownloadEntity, newItem: DownloadEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DownloadEntity, newItem: DownloadEntity) = oldItem == newItem
    }
}
