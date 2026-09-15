package com.lemezt.app.engine

import com.lemezt.app.model.CaptionTrack
import com.lemezt.app.model.FormatItem
import com.lemezt.app.model.FormatType
import com.lemezt.app.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object YouTubeParser {

    private class PersistentCookieJar : CookieJar {
        private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                cookieStore[c.name] = c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore.values.toList()
        }

        @Synchronized
        fun getCookieHeader(): String {
            return cookieStore.values.joinToString("; ") { "${it.name}=${it.value}" }
        }

        @Synchronized
        fun addRawCookie(name: String, value: String) {
            val c = Cookie.Builder()
                .domain("youtube.com")
                .path("/")
                .name(name)
                .value(value)
                .build()
            cookieStore[name] = c
        }
    }

    private val cookieJar = PersistentCookieJar()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .followRedirects(true)
        .build()

    // Regex to extract 11-character Video ID from any shared text or URL
    private val videoIdPattern = Pattern.compile(
        "(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|watch\\?v=|watch\\?.+&v=|shorts/|live/))([a-zA-Z0-9_-]{11})"
    )

    fun extractVideoId(text: String): String? {
        val matcher = videoIdPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun decodeHtml(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    suspend fun fetchVideoDetails(videoId: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            // STEP 1: Fetch YouTube watch page to obtain visitorData, signatureTimestamp & cookies
            val watchRequest = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val watchResponse = client.newCall(watchRequest).execute()
            
            // Extract raw Set-Cookie headers directly from response
            val setCookieHeaders = watchResponse.headers("Set-Cookie")
            for (header in setCookieHeaders) {
                val pair = header.substringBefore(";").trim()
                val eqIdx = pair.indexOf('=')
                if (eqIdx > 0) {
                    val name = pair.substring(0, eqIdx).trim()
                    val value = pair.substring(eqIdx + 1).trim()
                    cookieJar.addRawCookie(name, value)
                }
            }

            val watchHtml = watchResponse.body?.string() ?: ""

            // Extract visitorData
            var visitorData: String? = null
            val visitorPattern1 = Pattern.compile("\"VISITOR_DATA\":\\s*\"([^\"]+)\"")
            val vMatcher1 = visitorPattern1.matcher(watchHtml)
            if (vMatcher1.find()) {
                visitorData = vMatcher1.group(1)
            } else {
                val visitorPattern2 = Pattern.compile("\"visitorData\":\\s*\"([^\"]+)\"")
                val vMatcher2 = visitorPattern2.matcher(watchHtml)
                if (vMatcher2.find()) {
                    visitorData = vMatcher2.group(1)
                }
            }

            // Extract signatureTimestamp (sts)
            var signatureTimestamp = 20702
            val stsPattern = Pattern.compile("\"signatureTimestamp\":\\s*(\\d+)")
            val stsMatcher = stsPattern.matcher(watchHtml)
            if (stsMatcher.find()) {
                signatureTimestamp = stsMatcher.group(1)?.toIntOrNull() ?: 20702
            }

            // Extract default title from HTML title tag
            var pageTitle = "YouTube Video"
            val titlePattern = Pattern.compile("<title>(.*?)(?: - YouTube)?</title>")
            val titleMatcher = titlePattern.matcher(watchHtml)
            if (titleMatcher.find()) {
                val raw = titleMatcher.group(1) ?: "YouTube Video"
                pageTitle = decodeHtml(raw)
            }

            // STEP 2: Call Innertube API using VISIONOS client profile
            val jsonPayload = JSONObject().apply {
                val context = JSONObject().apply {
                    val clientObj = JSONObject().apply {
                        put("clientName", "VISIONOS")
                        put("clientVersion", "1.02")
                        put("deviceMake", "Apple")
                        put("deviceModel", "RealityDevice17,1")
                        put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")
                        put("osName", "visionOS")
                        put("osVersion", "26.5.23O471")
                        put("hl", "en")
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                        if (!visitorData.isNullOrEmpty()) {
                            put("visitorData", visitorData)
                        }
                    }
                    put("client", clientObj)
                }
                put("context", context)
                put("videoId", videoId)

                val playbackContext = JSONObject().apply {
                    val contentPlaybackContext = JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", signatureTimestamp)
                    }
                    put("contentPlaybackContext", contentPlaybackContext)
                }
                put("playbackContext", playbackContext)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val cookieHeaderStr = cookieJar.getCookieHeader()

            val playerRequestBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("X-Youtube-Client-Name", "101")
                .addHeader("X-Youtube-Client-Version", "1.02")
                .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")

            if (!visitorData.isNullOrEmpty()) {
                playerRequestBuilder.addHeader("X-Goog-Visitor-Id", visitorData)
            }
            if (cookieHeaderStr.isNotEmpty()) {
                playerRequestBuilder.addHeader("Cookie", cookieHeaderStr)
            }

            val response = client.newCall(playerRequestBuilder.build()).execute()
            val body = response.body?.string() ?: throw Exception("Empty response from player API")
            val root = JSONObject(body)

            // Verify Playability Status
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val playStatus = playabilityStatus?.optString("status")
            val playReason = playabilityStatus?.optString("reason")

            val streamingData = root.optJSONObject("streamingData")
            if (streamingData == null && playStatus != null && playStatus != "OK") {
                throw Exception(playReason ?: "Playback unavailable: $playStatus")
            }

            // Video Details
            val videoDetails = root.optJSONObject("videoDetails")
            val title = decodeHtml(videoDetails?.optString("title", pageTitle) ?: pageTitle)
            val author = videoDetails?.optString("author", "YouTube Channel") ?: "YouTube Channel"
            val lengthSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L
            val thumbnailHdUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

            // Streaming Data
            val videoFormats = mutableListOf<FormatItem>()
            val audioFormats = mutableListOf<FormatItem>()
            val aacAudioFormats = mutableListOf<FormatItem>()

            if (streamingData != null) {
                // 1. Progressive formats (both audio + video pre-muxed)
                val formatsArray = streamingData.optJSONArray("formats") ?: JSONArray()
                parseFormats(formatsArray, videoFormats, audioFormats, aacAudioFormats, isProgressive = true)

                // 2. Adaptive formats (DASH separate video & audio)
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                parseFormats(adaptiveFormats, videoFormats, audioFormats, aacAudioFormats, isProgressive = false)
            }

            // Sort audio formats so higher bitrates come first
            audioFormats.sortWith(compareByDescending<FormatItem> { 
                it.resolution.replace(" kbps", "").toIntOrNull() ?: 0 
            }.thenByDescending { it.itag })

            // Find best AAC audio stream (guaranteed native Android MediaMuxer compatibility)
            val bestAacAudio = aacAudioFormats.find { it.itag == 140 } 
                ?: aacAudioFormats.maxByOrNull { it.resolution.replace(" kbps", "").toIntOrNull() ?: 0 }
                ?: audioFormats.find { it.mimeType.contains("audio/mp4") || it.mimeType.contains("mp4a") }

            if (audioFormats.isEmpty()) {
                throw Exception(playReason ?: "No direct playable audio streams found")
            }

            // Captions
            val captionsList = mutableListOf<CaptionTrack>()
            val captionsObj = root.optJSONObject("captions")
            val tracklist = captionsObj?.optJSONObject("playerCaptionsTracklistRenderer")
            val tracksArray = tracklist?.optJSONArray("captionTracks")
            if (tracksArray != null) {
                for (i in 0 until tracksArray.length()) {
                    val track = tracksArray.getJSONObject(i)
                    val langCode = track.optString("languageCode", "en")
                    val langName = track.optJSONObject("name")?.optString("simpleText", langCode) ?: langCode
                    val url = track.optString("baseUrl", "")
                    if (url.isNotEmpty()) {
                        captionsList.add(CaptionTrack(langCode, langName, url))
                    }
                }
            }

            Result.success(
                VideoInfo(
                    videoId = videoId,
                    title = title,
                    author = author,
                    lengthSeconds = lengthSeconds,
                    thumbnailHdUrl = thumbnailHdUrl,
                    videoFormats = videoFormats,
                    audioFormats = audioFormats,
                    captions = captionsList,
                    bestAacAudioFormat = bestAacAudio
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun parseFormats(
        array: JSONArray,
        videoList: MutableList<FormatItem>,
        audioList: MutableList<FormatItem>,
        aacAudioList: MutableList<FormatItem>,
        isProgressive: Boolean
    ) {
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val itag = item.optInt("itag")
            val mimeType = item.optString("mimeType", "")
            val directUrl = item.optString("url", "")
            val qualityLabel = item.optString("qualityLabel", "")
            val bitrate = item.optInt("bitrate", 0)

            // Direct playable URL is strictly required
            if (directUrl.isEmpty()) continue

            val contentLength = item.optLong("contentLength", 0L)
            val approxSize = if (contentLength > 0) {
                String.format("%.1f MB", contentLength / (1024.0 * 1024.0))
            } else {
                "Auto"
            }

            // Prioritize video/mp4 for native MediaMuxer compatibility
            if (mimeType.contains("video/mp4") || mimeType.contains("avc") || isProgressive) {
                if (qualityLabel.isNotEmpty() && !videoList.any { it.resolution == qualityLabel }) {
                    videoList.add(
                        FormatItem(
                            id = "video_$itag",
                            title = "$qualityLabel MP4",
                            resolution = qualityLabel,
                            type = FormatType.VIDEO,
                            directUrl = directUrl,
                            itag = itag,
                            approxSizeMb = approxSize,
                            mimeType = mimeType,
                            hasAudio = isProgressive
                        )
                    )
                }
            } else if (mimeType.startsWith("audio/")) {
                val kbps = if (bitrate > 0) "${bitrate / 1000} kbps" else "128 kbps"
                val audioItem = FormatItem(
                    id = "audio_$itag",
                    title = "MP3 ($kbps)",
                    resolution = kbps,
                    type = FormatType.AUDIO,
                    directUrl = directUrl,
                    itag = itag,
                    approxSizeMb = approxSize,
                    mimeType = mimeType,
                    hasAudio = true
                )

                if (!audioList.any { it.resolution == kbps }) {
                    audioList.add(audioItem)
                }

                // If AAC / M4A (mp4a), record for video muxing
                if (mimeType.contains("audio/mp4") || mimeType.contains("mp4a") || itag == 140) {
                    aacAudioList.add(audioItem)
                }
            }
        }
    }
}
