package com.lemezt.app.feature.home

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lemezt.app.R
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.databinding.FragmentHomeBinding
import com.lemezt.app.databinding.ItemDownloadRowBinding
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.feature.downloadoptions.DownloadOptionsBottomSheet
import com.lemezt.app.feature.downloads.DownloadsAdapter
import com.lemezt.app.feature.downloads.DownloadsViewModel
import com.lemezt.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val downloadsViewModel: DownloadsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInputActions()
        setupActiveDownloadsSummary()

        binding.btnOpenDrawer.setOnClickListener {
            (activity as? com.lemezt.app.ui.MainActivity)?.openDrawer()
        }

        binding.fabMultiCollector.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "Overlay permission needed for Floating Collector. Opening Settings...", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + requireContext().packageName))
                startActivity(intent)
            } else {
                com.lemezt.app.feature.floating.FloatingCollectorService.start(requireContext())
                Toast.makeText(requireContext(), "Floating Collector active! Open YouTube and copy links.", Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun setupInputActions() {
        binding.btnPaste.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                binding.etUrl.setText(text)
                binding.tvInputError.visibility = View.GONE
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClear.setOnClickListener {
            binding.etUrl.setText("")
            binding.tvInputError.visibility = View.GONE
            binding.pbAnalyzing.visibility = View.GONE
        }

        binding.btnAnalyze.setOnClickListener {
            val input = binding.etUrl.text?.toString()?.trim() ?: ""
            val videoId = YouTubeParser.extractVideoId(input)

            if (videoId == null) {
                binding.tvInputError.text = "Please enter a valid YouTube video or short URL."
                binding.tvInputError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val validVideoId = videoId
            binding.tvInputError.visibility = View.GONE
            binding.pbAnalyzing.visibility = View.VISIBLE
            binding.btnAnalyze.isEnabled = false

            lifecycleScope.launch {
                val result = YouTubeParser.fetchVideoDetails(validVideoId)
                binding.pbAnalyzing.visibility = View.GONE
                binding.btnAnalyze.isEnabled = true

                result.onSuccess { info ->
                    val sheet = DownloadOptionsBottomSheet.newInstance(info, input)
                    sheet.show(parentFragmentManager, "DownloadOptionsSheet")
                }.onFailure { err ->
                    binding.tvInputError.text = err.message ?: "Failed to resolve video stream details."
                    binding.tvInputError.visibility = View.VISIBLE
                }
            }
        }

        binding.tvViewAllDownloads.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.navigation_downloads)
        }
    }

    private fun setupActiveDownloadsSummary() {
        val adapter = DownloadsAdapter(
            onPauseResumeClick = { item -> downloadsViewModel.togglePauseResume(item) },
            onCancelDeleteClick = { item -> downloadsViewModel.cancelOrDelete(item) },
            onItemClick = {}
        )

        binding.rvHomeActiveDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHomeActiveDownloads.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            downloadsViewModel.activeDownloads.collect { list ->
                if (list.isEmpty()) {
                    binding.activeDownloadsSection.visibility = View.GONE
                } else {
                    binding.activeDownloadsSection.visibility = View.VISIBLE
                    adapter.submitList(list.take(2))
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
