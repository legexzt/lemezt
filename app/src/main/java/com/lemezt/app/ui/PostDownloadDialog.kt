package com.lemezt.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.lemezt.app.databinding.DialogPostDownloadBinding
import com.lemezt.app.engine.AudioTagHelper
import com.lemezt.app.engine.DownloadResult
import com.lemezt.app.engine.MediaStoreHelper
import com.lemezt.app.model.FormatType
import java.io.File
import java.io.FileOutputStream

class PostDownloadDialog(
    context: Context,
    private val result: DownloadResult,
    private val onPickThumbnailRequest: () -> Unit,
    private val onComplete: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogPostDownloadBinding
    private var customThumbnailUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPostDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCancelable(false)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        initViews()
    }

    private fun initViews() {
        // Pre-fill file name with default video title
        binding.etFileName.setText(result.defaultTitle)

        // Show default thumbnail if available
        if (result.thumbnailFile != null && result.thumbnailFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(result.thumbnailFile.absolutePath)
            binding.ivCustomThumbnailPreview.setImageBitmap(bitmap)
            binding.tvThumbnailSource.text = "YouTube HD Cover"
        }

        // Change Thumbnail button
        binding.btnPickThumbnail.setOnClickListener {
            onPickThumbnailRequest()
        }

        // Cancel button
        binding.btnCancelPostDownload.setOnClickListener {
            // Delete temp files if user cancels post-download
            try { result.mediaFile.delete() } catch (_: Exception) {}
            try { result.thumbnailFile?.delete() } catch (_: Exception) {}
            try { result.captionFile?.delete() } catch (_: Exception) {}
            dismiss()
            onComplete()
        }

        // Save Final File
        binding.btnSaveFinalFile.setOnClickListener {
            saveFinalFile()
        }
    }

    fun setCustomThumbnail(uri: Uri) {
        customThumbnailUri = uri
        binding.ivCustomThumbnailPreview.setImageURI(uri)
        binding.tvThumbnailSource.text = "Custom User Thumbnail"
    }

    private fun saveFinalFile() {
        val customName = binding.etFileName.text?.toString()?.trim()
        val finalFileName = if (!customName.isNullOrEmpty()) customName else result.defaultTitle

        // 1. If Audio, re-tag with custom thumbnail or customized title if requested
        if (result.formatType == FormatType.AUDIO) {
            var coverToEmbed: File? = result.thumbnailFile
            if (customThumbnailUri != null) {
                try {
                    val tempCustomCover = File(context.cacheDir, "custom_cover_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(customThumbnailUri!!)?.use { input ->
                        FileOutputStream(tempCustomCover).use { output ->
                            input.copyTo(output)
                        }
                    }
                    coverToEmbed = tempCustomCover
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val lyricsText = result.captionFile?.let { AudioTagHelper.extractLyricsForEmbedding(it) }
            AudioTagHelper.tagAudioFile(
                audioFile = result.mediaFile,
                title = finalFileName,
                artist = result.artist,
                album = "lemezt",
                lyrics = lyricsText,
                coverFile = coverToEmbed
            )
        }

        val mimeType = if (result.formatType == FormatType.AUDIO) "audio/mpeg" else "video/mp4"

        // 2. Save media file into Downloads/lemezt
        MediaStoreHelper.saveFileToDownloads(
            context = context,
            sourceFile = result.mediaFile,
            desiredFileName = finalFileName,
            mimeType = mimeType
        )

        // 3. Save thumbnail (custom or default) as standalone image file
        if (customThumbnailUri != null) {
            try {
                val tempThumb = File(context.cacheDir, "custom_thumb_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(customThumbnailUri!!)?.use { input ->
                    FileOutputStream(tempThumb).use { output ->
                        input.copyTo(output)
                    }
                }
                MediaStoreHelper.saveFileToDownloads(
                    context = context,
                    sourceFile = tempThumb,
                    desiredFileName = "${finalFileName}_thumb",
                    mimeType = "image/jpeg"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (result.thumbnailFile != null && result.thumbnailFile.exists()) {
            MediaStoreHelper.saveFileToDownloads(
                context = context,
                sourceFile = result.thumbnailFile,
                desiredFileName = "${finalFileName}_thumb",
                mimeType = "image/jpeg"
            )
        }

        // 4. Save captions and synchronized lyrics (.lrc)
        if (result.captionFile != null && result.captionFile.exists()) {
            if (result.formatType == FormatType.AUDIO) {
                // Generate and save .lrc synchronized lyrics for music players
                val lrcContent = AudioTagHelper.convertSrtToLrc(result.captionFile)
                if (lrcContent.isNotEmpty()) {
                    MediaStoreHelper.saveLrcFileToDownloads(
                        context = context,
                        lrcContent = lrcContent,
                        baseFileName = finalFileName
                    )
                }
            } else {
                // Save SRT for video players
                MediaStoreHelper.saveFileToDownloads(
                    context = context,
                    sourceFile = result.captionFile,
                    desiredFileName = finalFileName,
                    mimeType = "text/plain"
                )
            }
        }

        Toast.makeText(context, "Saved to lemezt folder!", Toast.LENGTH_LONG).show()
        // Immediately delete source temp files so app storage remains 0 MB
        try { result.mediaFile.delete() } catch (_: Exception) {}
        try { result.thumbnailFile?.delete() } catch (_: Exception) {}
        try { result.captionFile?.delete() } catch (_: Exception) {}
        dismiss()
        onComplete()
    }
}
