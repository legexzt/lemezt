package com.lemezt.app.core.common

sealed class AppError(open val userMessage: String, open val cause: Throwable? = null) {
    data class InvalidUrl(override val userMessage: String = "Please enter a valid YouTube link.") : AppError(userMessage)
    data class UnsupportedSource(override val userMessage: String = "This media source is currently unsupported.") : AppError(userMessage)
    data class MediaUnavailable(override val userMessage: String = "Media is private, deleted, or unavailable.") : AppError(userMessage)
    data class NetworkFailure(override val userMessage: String = "Network error occurred. Download will auto-resume.", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class StorageFull(override val userMessage: String = "Not enough storage space available.") : AppError(userMessage)
    data class ProcessingFailure(override val userMessage: String = "Failed to process audio or video stream.", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class PermissionDenied(override val userMessage: String = "Permission required to complete action.") : AppError(userMessage)
    data class CancelledByUser(override val userMessage: String = "Download was cancelled.") : AppError(userMessage)
    data class Unknown(override val userMessage: String = "An unexpected error occurred.", override val cause: Throwable? = null) : AppError(userMessage, cause)
}
