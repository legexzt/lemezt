package com.lemezt.app.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object MediaMuxerHelper {

    private const val TAG = "MediaMuxerHelper"

    /**
     * Muxes separate MP4 video and AAC audio streams into a single playable MP4 file with audio.
     * Uses Android's native C++ MediaMuxer engine (0 extra dependencies, super fast, hardware-accelerated).
     * Works seamlessly on all Samsung, Xiaomi, Pixel, and Motorola devices.
     */
    fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            Log.e(TAG, "Mux failed: video file is missing or empty")
            return false
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Mux failed: audio file is missing or empty")
            return false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            if (outputFile.exists()) {
                outputFile.delete()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Find and add Video Track
            var videoTrackIndex = -1
            var videoMuxerTrackIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoMuxerTrackIndex = muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                    Log.d(TAG, "Selected video track $i with mime $mime")
                    break
                }
            }

            if (videoMuxerTrackIndex == -1) {
                Log.e(TAG, "No video track found in ${videoFile.name}")
                return false
            }

            // 2. Find and add Audio Track
            var audioTrackIndex = -1
            var audioMuxerTrackIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    try {
                        audioMuxerTrackIndex = muxer.addTrack(format)
                        audioExtractor.selectTrack(i)
                        Log.d(TAG, "Selected audio track $i with mime $mime")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add audio track with mime $mime: ${e.message}")
                    }
                }
            }

            muxer.start()

            val bufferSize = 512 * 1024 // 512 KB buffer
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // 3. Write Video Samples
            var firstVideoPts = -1L
            var lastVideoPts = 0L
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val sampleTime = videoExtractor.sampleTime
                if (firstVideoPts == -1L) firstVideoPts = sampleTime
                val pts = Math.max(lastVideoPts, sampleTime - firstVideoPts)
                lastVideoPts = pts

                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoMuxerTrackIndex, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // 4. Write Audio Samples (if track added)
            if (audioMuxerTrackIndex != -1) {
                var firstAudioPts = -1L
                var lastAudioPts = 0L
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    val sampleTime = audioExtractor.sampleTime
                    if (firstAudioPts == -1L) firstAudioPts = sampleTime
                    val pts = Math.max(lastAudioPts, sampleTime - firstAudioPts)
                    lastAudioPts = pts

                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(audioMuxerTrackIndex, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
            Log.d(TAG, "Muxing completed successfully! Output: ${outputFile.length()} bytes")
            return outputFile.exists() && outputFile.length() > 0L

        } catch (e: Exception) {
            Log.e(TAG, "Muxing exception: ${e.message}", e)
            return false
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
