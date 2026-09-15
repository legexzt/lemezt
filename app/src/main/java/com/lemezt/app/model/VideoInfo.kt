package com.lemezt.app.model

enum class FormatType {
    VIDEO,
    AUDIO
}

data class FormatItem(
    val id: String,
    val title: String,
    val resolution: String,
    val type: FormatType,
    val directUrl: String?,
    val itag: Int,
    val approxSizeMb: String = "Auto",
    val mimeType: String = "",
    val hasAudio: Boolean = false
)

data class CaptionTrack(
    val languageCode: String,
    val languageName: String,
    val baseUrl: String
)

data class VideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Long,
    val thumbnailHdUrl: String,
    val videoFormats: List<FormatItem>,
    val audioFormats: List<FormatItem>,
    val captions: List<CaptionTrack>,
    val bestAacAudioFormat: FormatItem? = null
)
