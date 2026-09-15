package com.lemezt.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lemezt.app.R
import com.lemezt.app.databinding.ActivityBatchReviewBinding
import com.lemezt.app.databinding.ItemBatchVideoBinding
import com.lemezt.app.engine.DownloadQueueManager
import com.lemezt.app.engine.QueueItem
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.model.FormatType
import com.lemezt.app.service.BatchCollectorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class BatchReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchReviewBinding
    private val adapter = BatchItemAdapter()
    private val items = mutableListOf<BatchCardItem>()

    data class BatchCardItem(
        val videoId: String,
        var title: String = "YouTube Video",
        var author: String = "YouTube • Resolving...",
        var thumbUrl: String = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        var selectedType: FormatType = FormatType.AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadCollectedItems()
    }

    private fun setupUI() {
        binding.rvBatchItems.layoutManager = LinearLayoutManager(this)
        binding.rvBatchItems.adapter = adapter

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnBulkMp3.setOnClickListener {
            items.forEach { it.selectedType = FormatType.AUDIO }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Set all to MP3 Audio", Toast.LENGTH_SHORT).show()
        }

        binding.btnBulkMp4.setOnClickListener {
            items.forEach { it.selectedType = FormatType.VIDEO }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Set all to MP4 Video", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartBatchDownload.setOnClickListener {
            if (items.isEmpty()) {
                Toast.makeText(this, "No items in batch to download", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Convert to QueueItems
            val queueItems = items.map { card ->
                QueueItem(
                    videoId = card.videoId,
                    title = card.title,
                    artist = card.author,
                    thumbnailUrl = card.thumbUrl,
                    desiredType = card.selectedType
                )
            }

            // Enqueue all into the 2-by-2 concurrent queue engine
            DownloadQueueManager.enqueue(queueItems, applicationContext)

            // Clear batch collector
            BatchCollectorService.clearCollected()

            Toast.makeText(this, "Added ${queueItems.size} items to Download Queue!", Toast.LENGTH_LONG).show()

            // Navigate back to MainActivity
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("SHOW_QUEUE", true)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun loadCollectedItems() {
        val collected = BatchCollectorService.collectedLinks.value
        items.clear()
        collected.forEach { vid ->
            items.add(BatchCardItem(videoId = vid))
        }

        binding.tvTotalCountBadge.text = "${items.size} Items"
        binding.btnStartBatchDownload.text = "🚀 Start Batch Download (${items.size})"
        adapter.notifyDataSetChanged()

        // Asynchronously resolve titles & thumbnails
        items.forEachIndexed { index, cardItem ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = YouTubeParser.fetchVideoDetails(cardItem.videoId)
                    result.onSuccess { info ->
                        cardItem.title = info.title
                        cardItem.author = info.author
                        cardItem.thumbUrl = info.thumbnailHdUrl
                        withContext(Dispatchers.Main) {
                            adapter.notifyItemChanged(index)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    inner class BatchItemAdapter : RecyclerView.Adapter<BatchItemAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemBatchVideoBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemBatchVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.itemBinding.tvItemTitle.text = item.title
            holder.itemBinding.tvItemAuthor.text = item.author

            // Radio button selection
            if (item.selectedType == FormatType.AUDIO) {
                holder.itemBinding.rbMp3.isChecked = true
            } else {
                holder.itemBinding.rbMp4.isChecked = true
            }

            holder.itemBinding.rgItemFormat.setOnCheckedChangeListener { _, checkedId ->
                item.selectedType = if (checkedId == R.id.rbMp4) FormatType.VIDEO else FormatType.AUDIO
            }

            holder.itemBinding.btnRemoveItem.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != -1 && currentPos < items.size) {
                    val removed = items.removeAt(currentPos)
                    BatchCollectorService.removeVideoId(removed.videoId)
                    notifyItemRemoved(currentPos)
                    binding.tvTotalCountBadge.text = "${items.size} Items"
                    binding.btnStartBatchDownload.text = "🚀 Start Batch Download (${items.size})"
                }
            }

            // Thumbnail loading
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder().url(item.thumbUrl).build()
                    val res = client.newCall(req).execute()
                    val bytes = res.body?.bytes()
                    if (bytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        withContext(Dispatchers.Main) {
                            holder.itemBinding.ivItemThumb.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
