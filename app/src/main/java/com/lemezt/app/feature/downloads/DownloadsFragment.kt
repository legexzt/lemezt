package com.lemezt.app.feature.downloads

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.lemezt.app.databinding.FragmentDownloadsBinding
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DownloadsViewModel by activityViewModels()
    private lateinit var adapter: DownloadsAdapter

    private var currentTabPosition = 0 // 0: Active, 1: Completed

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DownloadsAdapter(
            onPauseResumeClick = { item -> viewModel.togglePauseResume(item) },
            onCancelDeleteClick = { item -> viewModel.cancelOrDelete(item) },
            onItemClick = { item ->
                if (item.status == "COMPLETED") {
                    val mediaPath = item.publicUriString ?: item.relativeTempPath
                    if (!mediaPath.isNullOrEmpty()) {
                        if (item.formatType == "VIDEO" || item.container.lowercase() == "mp4") {
                            com.lemezt.app.feature.player.VideoPlayerActivity.start(
                                requireContext(),
                                mediaPath,
                                item.title
                            )
                        } else {
                            com.lemezt.app.feature.player.AudioPlayerBottomSheet.newInstance(
                                uri = mediaPath,
                                title = item.title,
                                artist = item.author ?: "lemezt",
                                artwork = item.thumbnailUrl
                            ).show(parentFragmentManager, "AudioPlayerSheet")
                        }
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Media file not found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        binding.rvDownloadsList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloadsList.adapter = adapter

        binding.btnClearHistory.setOnClickListener {
            viewModel.clearCompletedHistory()
        }

        binding.tabLayoutDownloads.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabPosition = tab?.position ?: 0
                renderList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeDownloads.collect {
                if (currentTabPosition == 0) renderList()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.completedDownloads.collect {
                if (currentTabPosition == 1) renderList()
            }
        }
    }

    private fun renderList() {
        val list = if (currentTabPosition == 0) {
            binding.btnClearHistory.visibility = View.GONE
            viewModel.activeDownloads.value
        } else {
            binding.btnClearHistory.visibility = View.VISIBLE
            viewModel.completedDownloads.value
        }

        adapter.submitList(list)
        binding.tvDownloadsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
