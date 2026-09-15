package com.lemezt.app.feature.downloadoptions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.lemezt.app.R
import com.lemezt.app.databinding.BottomSheetDownloadOptionsBinding
import com.lemezt.app.domain.model.OutputMediaType
import com.lemezt.app.domain.model.OutputPlan
import com.lemezt.app.feature.downloads.DownloadsViewModel
import com.lemezt.app.model.FormatItem
import com.lemezt.app.model.VideoInfo
import com.lemezt.app.ui.MainActivity

class DownloadOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDownloadOptionsBinding? = null
    private val binding get() = _binding!!

    private val downloadsViewModel: DownloadsViewModel by activityViewModels()

    private var videoInfo: VideoInfo? = null
    private var sourceUrl: String = ""

    companion object {
        fun newInstance(info: VideoInfo, url: String): DownloadOptionsBottomSheet {
            val sheet = DownloadOptionsBottomSheet()
            sheet.videoInfo = info
            sheet.sourceUrl = url
            return sheet
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDownloadOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val info = videoInfo ?: run { dismiss(); return }

        binding.tvSheetTitle.text = info.title
        val mins = info.lengthSeconds / 60
        val secs = info.lengthSeconds % 60
        val durationStr = String.format("%d:%02d", mins, secs)
        binding.tvSheetAuthor.text = "${info.author} • $durationStr"

        binding.btnSheetClose.setOnClickListener { dismiss() }

        // Populate options initially with Video
        renderFormatOptions(isAudio = false)

        binding.tabLayoutFormat.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isAudio = tab?.position == 1
                renderFormatOptions(isAudio)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnConfirmDownload.setOnClickListener {
            val checkedId = binding.rgFormatOptions.checkedRadioButtonId
            if (checkedId == -1) {
                Toast.makeText(requireContext(), "Please select an output format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val checkedRb = binding.rgFormatOptions.findViewById<RadioButton>(checkedId)
            val selectedFormat = checkedRb.tag as? FormatItem ?: return@setOnClickListener

            val isAudio = binding.tabLayoutFormat.selectedTabPosition == 1
            val mediaType = if (isAudio) OutputMediaType.AUDIO else OutputMediaType.VIDEO
            val container = if (isAudio) "m4a" else "mp4"

            val plan = OutputPlan(
                mediaType = mediaType,
                container = container,
                qualityLabel = selectedFormat.resolution,
                estimatedBytes = null,
                isProgressive = selectedFormat.hasAudio,
                embedCoverArt = binding.cbEmbedCover.isChecked,
                saveLyrics = binding.cbSaveLyrics.isChecked
            )

            downloadsViewModel.enqueueDownload(
                mediaId = info.videoId,
                sourceUrl = sourceUrl,
                title = info.title,
                author = info.author,
                thumbnailUrl = info.thumbnailHdUrl,
                plan = plan
            )

            dismiss()

            activity?.let { act ->
                val root = act.findViewById<View>(R.id.mainRoot) ?: view
                Snackbar.make(root, "Added to downloads", Snackbar.LENGTH_LONG)
                    .setAction("View") {
                        (act as? MainActivity)?.selectTab(R.id.navigation_downloads)
                    }.show()
            }
        }
    }

    private fun renderFormatOptions(isAudio: Boolean) {
        val info = videoInfo ?: return
        binding.rgFormatOptions.removeAllViews()

        if (!isAudio) {
            // Video options
            info.videoFormats.forEachIndexed { index, fmt ->
                val rb = RadioButton(requireContext()).apply {
                    id = View.generateViewId()
                    text = "${fmt.resolution} MP4 • H.264 + AAC • ${fmt.approxSizeMb}"
                    tag = fmt
                    setTextColor(requireContext().getColor(R.color.lemezt_text_primary))
                    isChecked = (index == 0)
                }
                binding.rgFormatOptions.addView(rb)
            }
        } else {
            // Audio options: Pristine native M4A (AAC)
            val aacFormat = info.bestAacAudioFormat ?: info.audioFormats.firstOrNull()
            if (aacFormat != null) {
                val rb = RadioButton(requireContext()).apply {
                    id = View.generateViewId()
                    text = "M4A • Original AAC (${aacFormat.resolution}) • Lossless"
                    tag = aacFormat
                    setTextColor(requireContext().getColor(R.color.lemezt_text_primary))
                    isChecked = true
                }
                binding.rgFormatOptions.addView(rb)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
