package com.lemezt.app.service

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import com.lemezt.app.engine.YouTubeParser

/**
 * Trampoline activity that receives window focus to read clipboard
 * on Android 10+ (where background services are denied clipboard access).
 * Starts invisibly, waits for onWindowFocusChanged(true), reads clipboard,
 * notifies BatchCollectorService, and finishes.
 */
class ClipboardReaderActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardReader"
    }

    private var hasProcessed = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Attach a minimal view so WindowManager allocates input channel and gives focus
        val dummyView = View(this)
        setContentView(dummyView)
        overridePendingTransition(0, 0)

        // Safety timeout in case focus isn't received within 800ms
        handler.postDelayed({
            if (!hasProcessed && !isFinishing) {
                Log.d(TAG, "Focus timeout reached, attempting read...")
                hasProcessed = true
                processClipboard()
            }
        }, 800)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, hasProcessed=$hasProcessed")
        if (hasFocus && !hasProcessed) {
            hasProcessed = true
            handler.removeCallbacksAndMessages(null)
            // Post with slight delay to ensure system_server registers the focus switch
            window.decorView.postDelayed({
                processClipboard()
            }, 60)
        }
    }

    private fun processClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            Log.d(TAG, "processClipboard: clip=$clip, itemCount=${clip?.itemCount}")

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString().trim()
                Log.d(TAG, "Captured text: $text")

                val videoId = YouTubeParser.extractVideoId(text)
                if (videoId != null) {
                    val added = BatchCollectorService.addVideoId(videoId, this)
                    val count = BatchCollectorService.collectedLinks.value.size
                    if (added) {
                        Toast.makeText(
                            applicationContext,
                            "Link #$count Captured! ($videoId)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            applicationContext,
                            "Link already in batch list (#$count)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.d(TAG, "Not a YouTube link: $text")
                    Toast.makeText(
                        applicationContext,
                        "Not a YouTube link: ${text.take(25)}...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(applicationContext, "Clipboard is empty or denied", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading clipboard", e)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
