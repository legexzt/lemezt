package com.lemezt.app.domain.model

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PROCESSING,
    SAVING,
    COMPLETED,
    PAUSED_BY_USER,
    WAITING_FOR_NETWORK,
    FAILED,
    CANCELLED;

    fun isActive(): Boolean = this in listOf(QUEUED, RESOLVING, DOWNLOADING, PROCESSING, SAVING, WAITING_FOR_NETWORK)
    fun isPaused(): Boolean = this in listOf(PAUSED_BY_USER, WAITING_FOR_NETWORK)
    fun isTerminal(): Boolean = this in listOf(COMPLETED, FAILED, CANCELLED)
}
