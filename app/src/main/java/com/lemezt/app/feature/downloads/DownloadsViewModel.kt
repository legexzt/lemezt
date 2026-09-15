package com.lemezt.app.feature.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lemezt.app.core.database.entity.DownloadEntity
import com.lemezt.app.data.repository.DownloadRepositoryImpl
import com.lemezt.app.domain.model.OutputPlan
import com.lemezt.app.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DownloadRepository = DownloadRepositoryImpl(application)

    val activeDownloads: StateFlow<List<DownloadEntity>> = repository.getActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedDownloads: StateFlow<List<DownloadEntity>> = repository.getCompletedDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun enqueueDownload(
        mediaId: String,
        sourceUrl: String,
        title: String,
        author: String,
        thumbnailUrl: String?,
        plan: OutputPlan
    ) {
        viewModelScope.launch {
            repository.enqueueDownload(mediaId, sourceUrl, title, author, thumbnailUrl, plan)
        }
    }

    fun togglePauseResume(item: DownloadEntity) {
        viewModelScope.launch {
            if (item.status == "DOWNLOADING" || item.status == "QUEUED") {
                repository.pauseDownload(item.id)
            } else if (item.status == "PAUSED_BY_USER" || item.status == "WAITING_FOR_NETWORK" || item.status == "FAILED") {
                repository.resumeDownload(item.id)
            }
        }
    }

    fun cancelOrDelete(item: DownloadEntity) {
        viewModelScope.launch {
            if (item.status == "COMPLETED" || item.status == "FAILED" || item.status == "CANCELLED") {
                repository.deleteDownload(item.id)
            } else {
                repository.cancelDownload(item.id)
            }
        }
    }

    fun clearCompletedHistory() {
        viewModelScope.launch {
            repository.clearCompletedHistory()
        }
    }
}
