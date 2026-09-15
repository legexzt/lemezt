package com.lemezt.app.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

object AudioTagHelper {

    private const val TAG = "AudioTagHelper"

    /**
     * Tags an audio file with Title, Artist, Album, Lyrics, and Cover Artwork.
     * Supports both MP4/M4A containers (used by YouTube itag 140 / AAC) and MP3 containers.
     */
    fun tagAudioFile(
        audioFile: File,
        title: String?,
        artist: String?,
        album: String? = "lemezt",
        lyrics: String? = null,
        coverFile: File? = null
    ): Boolean {
        if (!audioFile.exists() || audioFile.length() < 32) return false

        val coverBytes = if (coverFile != null && coverFile.exists()) {
            try {
                coverFile.readBytes()
            } catch (e: Exception) {
                null
            }
        } else null

        return try {
            if (isMp4Container(audioFile)) {
                embedMp4Metadata(audioFile, title, artist, album, lyrics, coverBytes)
            } else if (isMp3Container(audioFile)) {
                embedMp3Metadata(audioFile, title, artist, album, lyrics, coverBytes)
            } else {
                // Fallback to MP4 container tagging
                embedMp4Metadata(audioFile, title, artist, album, lyrics, coverBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to tag audio file", e)
            false
        }
    }

    /**
     * Checks if the file is an ISO MPEG-4 container (ftyp atom).
     */
    fun isMp4Container(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read >= 8) {
                    val atomType = String(header, 4, 4, Charsets.ISO_8859_1)
                    return atomType == "ftyp"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Checks if the file is an MP3 file (ID3 header or MPEG sync word).
     */
    fun isMp3Container(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read >= 3 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    return true
                }
                if (read >= 2 && (header[0].toInt() and 0xFF) == 0xFF && ((header[1].toInt() and 0xE0) == 0xE0)) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // ==========================================
    // MP4 / M4A (AAC) Container Tagging
    // ==========================================

    private fun embedMp4Metadata(
        file: File,
        title: String?,
        artist: String?,
        album: String?,
        lyrics: String?,
        coverBytes: ByteArray?
    ): Boolean {
        val data = file.readBytes()
        var offset = 0
        var moovStart = -1
        var moovSize = -1

        while (offset + 8 <= data.size) {
            val sz = readBigEndianInt(data, offset)
            val atom = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            if (atom == "moov") {
                moovStart = offset
                moovSize = sz
                break
            }
            if (sz <= 0 || sz > data.size - offset) break
            offset += sz
        }

        if (moovStart == -1 || moovSize <= 8) {
            Log.w(TAG, "No moov atom found in MP4 file")
            return false
        }

        val moovEnd = moovStart + moovSize
        val moovPayload = data.copyOfRange(moovStart + 8, moovEnd)

        // Find existing udta atom if any
        var sub = 0
        var oldUdtaStart = -1
        var oldUdtaSize = 0

        while (sub + 8 <= moovPayload.size) {
            val ssz = readBigEndianInt(moovPayload, sub)
            val satom = String(moovPayload, sub + 4, 4, Charsets.ISO_8859_1)
            if (satom == "udta") {
                oldUdtaStart = sub
                oldUdtaSize = ssz
                break
            }
            if (ssz <= 0 || ssz > moovPayload.size - sub) break
            sub += ssz
        }

        val newUdta = buildUdtaAtom(title, artist, album, lyrics, coverBytes)
        val newMoovPayload = ByteArrayOutputStream()

        if (oldUdtaStart != -1) {
            // Replace existing udta atom
            newMoovPayload.write(moovPayload, 0, oldUdtaStart)
            newMoovPayload.write(newUdta)
            val remainderStart = oldUdtaStart + oldUdtaSize
            if (remainderStart < moovPayload.size) {
                newMoovPayload.write(moovPayload, remainderStart, moovPayload.size - remainderStart)
            }
        } else {
            // Append udta to moov
            newMoovPayload.write(moovPayload)
            newMoovPayload.write(newUdta)
        }

        val newMoovPayloadBytes = newMoovPayload.toByteArray()
        val newMoovSize = 8 + newMoovPayloadBytes.size

        val tempFile = File(file.parentFile, "tagged_${System.currentTimeMillis()}.tmp")
        FileOutputStream(tempFile).use { out ->
            // 1. Everything before moov (ftyp etc.)
            out.write(data, 0, moovStart)
            // 2. Updated moov header
            out.write(intToBytes(newMoovSize))
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            // 3. Updated moov payload with udta
            out.write(newMoovPayloadBytes)
            // 4. Everything after moov (sidx, moofs, mdats)
            if (moovEnd < data.size) {
                out.write(data, moovEnd, data.size - moovEnd)
            }
        }

        if (tempFile.length() > 0) {
            if (file.delete()) {
                tempFile.renameTo(file)
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            return true
        }
        return false
    }

    private fun buildUdtaAtom(
        title: String?,
        artist: String?,
        album: String?,
        lyrics: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val ilstBody = ByteArrayOutputStream()

        // ©nam (Title)
        if (!title.isNullOrEmpty()) {
            ilstBody.write(buildIlstItem(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title.toByteArray(Charsets.UTF_8), dataType = 1))
        }

        // ©ART (Artist)
        if (!artist.isNullOrEmpty()) {
            ilstBody.write(buildIlstItem(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), artist.toByteArray(Charsets.UTF_8), dataType = 1))
        }

        // ©alb (Album)
        if (!album.isNullOrEmpty()) {
            ilstBody.write(buildIlstItem(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), album.toByteArray(Charsets.UTF_8), dataType = 1))
        }

        // ©lyr (Lyrics)
        if (!lyrics.isNullOrEmpty()) {
            ilstBody.write(buildIlstItem(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), lyrics.toByteArray(Charsets.UTF_8), dataType = 1))
        }

        // covr (Cover Image)
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val isPng = coverBytes.size > 8 &&
                    coverBytes[0] == 0x89.toByte() &&
                    coverBytes[1] == 'P'.code.toByte() &&
                    coverBytes[2] == 'N'.code.toByte() &&
                    coverBytes[3] == 'G'.code.toByte()
            val coverType = if (isPng) 14 else 13 // 13 = JPEG, 14 = PNG
            ilstBody.write(buildIlstItem("covr".toByteArray(Charsets.ISO_8859_1), coverBytes, dataType = coverType))
        }

        val ilstBytes = ilstBody.toByteArray()
        val ilstAtom = ByteArrayOutputStream().apply {
            write(intToBytes(8 + ilstBytes.size))
            write("ilst".toByteArray(Charsets.ISO_8859_1))
            write(ilstBytes)
        }.toByteArray()

        // hdlr atom inside meta: size 33, 'hdlr', 8 zeros, 'mdirappl', 9 zeros
        val hdlrAtom = ByteArrayOutputStream().apply {
            write(intToBytes(33))
            write("hdlr".toByteArray(Charsets.ISO_8859_1))
            write(ByteArray(8))
            write("mdirappl".toByteArray(Charsets.ISO_8859_1))
            write(ByteArray(9))
        }.toByteArray()

        // meta atom: size, 'meta', version/flags (4 zeros), hdlr, ilst
        val metaBody = ByteArrayOutputStream().apply {
            write(ByteArray(4)) // version & flags
            write(hdlrAtom)
            write(ilstAtom)
        }.toByteArray()

        val metaAtom = ByteArrayOutputStream().apply {
            write(intToBytes(8 + metaBody.size))
            write("meta".toByteArray(Charsets.ISO_8859_1))
            write(metaBody)
        }.toByteArray()

        // udta atom
        return ByteArrayOutputStream().apply {
            write(intToBytes(8 + metaAtom.size))
            write("udta".toByteArray(Charsets.ISO_8859_1))
            write(metaAtom)
        }.toByteArray()
    }

    private fun buildIlstItem(tagNameBytes: ByteArray, dataBytes: ByteArray, dataType: Int): ByteArray {
        val dataAtom = buildDataAtom(dataBytes, dataType)
        val itemSize = 8 + dataAtom.size
        return ByteArrayOutputStream().apply {
            write(intToBytes(itemSize))
            write(tagNameBytes)
            write(dataAtom)
        }.toByteArray()
    }

    private fun buildDataAtom(dataBytes: ByteArray, dataType: Int): ByteArray {
        val payloadSize = 8 + dataBytes.size
        val atomSize = 8 + payloadSize
        return ByteArrayOutputStream().apply {
            write(intToBytes(atomSize))
            write("data".toByteArray(Charsets.ISO_8859_1))
            write(intToBytes(dataType))
            write(ByteArray(4)) // locale 0
            write(dataBytes)
        }.toByteArray()
    }

    // ==========================================
    // MP3 ID3v2.3 Tagging
    // ==========================================

    private fun embedMp3Metadata(
        file: File,
        title: String?,
        artist: String?,
        album: String?,
        lyrics: String?,
        coverBytes: ByteArray?
    ): Boolean {
        val fileBytes = file.readBytes()
        var audioStart = 0

        // Strip existing ID3v2 tag if present
        if (fileBytes.size >= 10 && fileBytes[0] == 'I'.code.toByte() && fileBytes[1] == 'D'.code.toByte() && fileBytes[2] == '3'.code.toByte()) {
            val id3Size = decodeSynchsafe(fileBytes, 6)
            audioStart = 10 + id3Size
        }

        val frames = ByteArrayOutputStream()

        if (!title.isNullOrEmpty()) {
            frames.write(buildId3v2TextFrame("TIT2", title))
        }
        if (!artist.isNullOrEmpty()) {
            frames.write(buildId3v2TextFrame("TPE1", artist))
        }
        if (!album.isNullOrEmpty()) {
            frames.write(buildId3v2TextFrame("TALB", album))
        }
        if (!lyrics.isNullOrEmpty()) {
            frames.write(buildId3v2LyricsFrame(lyrics))
        }
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            frames.write(buildId3v2CoverFrame(coverBytes))
        }

        val framesBytes = frames.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(3, 0, 0)) // v2.3.0, flags 0
            write(encodeSynchsafe(framesBytes.size))
        }.toByteArray()

        val tempFile = File(file.parentFile, "tagged_mp3_${System.currentTimeMillis()}.tmp")
        FileOutputStream(tempFile).use { out ->
            out.write(header)
            out.write(framesBytes)
            if (audioStart < fileBytes.size) {
                out.write(fileBytes, audioStart, fileBytes.size - audioStart)
            }
        }

        if (tempFile.length() > 0) {
            if (file.delete()) {
                tempFile.renameTo(file)
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            return true
        }
        return false
    }

    private fun buildId3v2TextFrame(frameId: String, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().apply {
            write(0x03) // UTF-8 encoding
            write(textBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(frameId.toByteArray(Charsets.ISO_8859_1))
            write(intToBytes(payload.size))
            write(byteArrayOf(0, 0)) // Flags
            write(payload)
        }.toByteArray()
    }

    private fun buildId3v2LyricsFrame(lyrics: String): ByteArray {
        val lyricsBytes = lyrics.toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().apply {
            write(0x03) // UTF-8 encoding
            write("eng".toByteArray(Charsets.ISO_8859_1))
            write(0x00) // Content descriptor terminator
            write(lyricsBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("USLT".toByteArray(Charsets.ISO_8859_1))
            write(intToBytes(payload.size))
            write(byteArrayOf(0, 0)) // Flags
            write(payload)
        }.toByteArray()
    }

    private fun buildId3v2CoverFrame(coverBytes: ByteArray): ByteArray {
        val isPng = coverBytes.size > 8 &&
                coverBytes[0] == 0x89.toByte() &&
                coverBytes[1] == 'P'.code.toByte() &&
                coverBytes[2] == 'N'.code.toByte() &&
                coverBytes[3] == 'G'.code.toByte()

        val mime = if (isPng) "image/png\u0000" else "image/jpeg\u0000"
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)

        val payload = ByteArrayOutputStream().apply {
            write(0x00) // ISO-8859-1 encoding for MIME
            write(mimeBytes)
            write(0x03) // Picture type: Cover (front)
            write(0x00) // Description null terminator
            write(coverBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("APIC".toByteArray(Charsets.ISO_8859_1))
            write(intToBytes(payload.size))
            write(byteArrayOf(0, 0)) // Flags
            write(payload)
        }.toByteArray()
    }

    // ==========================================
    // Captions & Synchronized Lyrics (.lrc)
    // ==========================================

    /**
     * Converts an SRT or XML caption file to standard synchronized .lrc format.
     */
    fun convertSrtToLrc(captionFile: File): String {
        if (!captionFile.exists()) return ""
        val content = try {
            captionFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
        return convertCaptionStringToLrc(content)
    }

    /**
     * Converts caption content (SRT or YouTube XML) to synchronized LRC string.
     */
    fun convertCaptionStringToLrc(content: String): String {
        val sb = StringBuilder()

        // Check if YouTube timedtext XML format
        if (content.contains("<text") && content.contains("start=")) {
            val xmlRegex = Regex("""<text\s+start="([\d.]+)"[^>]*>(.*?)</text>""")
            for (match in xmlRegex.findAll(content)) {
                val startSec = match.groupValues[1].toDoubleOrNull() ?: continue
                val text = match.groupValues[2]
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("<[^>]*>"), "")
                    .trim()

                if (text.isNotEmpty()) {
                    val m = (startSec / 60).toInt()
                    val s = (startSec % 60).toInt()
                    val cs = ((startSec - startSec.toInt()) * 100).toInt()
                    val ts = String.format(Locale.US, "[%02d:%02d.%02d]", m, s, cs)
                    sb.append(ts).append(text).append("\n")
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }

        // Standard SRT / VTT format
        val lines = content.lines()
        var currentTimestamp: String? = null
        val timestampRegex = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{2,3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{2,3})""")

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.all { it.isDigit() }) continue

            val match = timestampRegex.find(line)
            if (match != null) {
                val h = match.groupValues[1].toIntOrNull() ?: 0
                val m = match.groupValues[2].toIntOrNull() ?: 0
                val s = match.groupValues[3].toIntOrNull() ?: 0
                val ms = match.groupValues[4]
                val totalMinutes = h * 60 + m
                val centiseconds = if (ms.length >= 2) ms.substring(0, 2) else ms.padEnd(2, '0')
                currentTimestamp = String.format(Locale.US, "[%02d:%02d.%s]", totalMinutes, s, centiseconds)
            } else if (currentTimestamp != null) {
                val cleanText = line.replace(Regex("<[^>]*>"), "").trim()
                if (cleanText.isNotEmpty()) {
                    sb.append(currentTimestamp).append(cleanText).append("\n")
                    currentTimestamp = null
                }
            }
        }
        return sb.toString()
    }

    /**
     * Extracts lyrics suitable for embedding directly inside the audio tag.
     */
    fun extractLyricsForEmbedding(captionFile: File): String {
        val lrc = convertSrtToLrc(captionFile)
        if (lrc.isNotEmpty()) return lrc

        // Fallback to plain text
        return try {
            captionFile.readLines(Charsets.UTF_8)
                .filter { it.trim().isNotEmpty() && !it.trim().all { c -> c.isDigit() } && !it.contains("-->") }
                .joinToString("\n") { it.replace(Regex("<[^>]*>"), "").trim() }
        } catch (e: Exception) {
            ""
        }
    }

    // ==========================================
    // Binary Utility Functions
    // ==========================================

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun encodeSynchsafe(size: Int): ByteArray {
        return byteArrayOf(
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte()
        )
    }

    private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
                ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
                ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }
}
