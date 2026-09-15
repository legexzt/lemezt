package com.lemezt.app.domain.model

enum class OutputMediaType {
    VIDEO,
    AUDIO
}

data class OutputPlan(
    val mediaType: OutputMediaType,
    val container: String,        // "mp4", "m4a"
    val qualityLabel: String,     // "1080p", "720p", "128 kbps"
    val videoCodec: String? = null,
    val audioCodec: String = "AAC",
    val estimatedBytes: Long? = null,
    val videoStreamUrl: String? = null,
    val audioStreamUrl: String? = null,
    val isProgressive: Boolean = false,
    val embedCoverArt: Boolean = true,
    val saveLyrics: Boolean = true
)
