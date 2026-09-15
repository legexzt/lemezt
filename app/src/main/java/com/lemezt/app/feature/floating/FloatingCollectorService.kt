package com.lemezt.app.feature.floating

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.lemezt.app.R
import com.lemezt.app.databinding.LayoutFloatingCollectorBinding
import com.lemezt.app.domain.model.OutputMediaType
import com.lemezt.app.domain.model.OutputPlan
import com.lemezt.app.engine.YouTubeParser
import com.lemezt.app.transfer.TransferCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

class FloatingCollectorService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var binding: LayoutFloatingCollectorBinding

    private val collectedUrls = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        var isRunning = false
        fun start(context: Context) {
            val intent = Intent(context, FloatingCollectorService::class.java)
            context.startService(intent)
        }
        fun stop(context: Context) {
            val intent = Intent(context, FloatingCollectorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_Lemezt)
        val inflater = LayoutInflater.from(themedContext)
        binding = LayoutFloatingCollectorBinding.inflate(inflater)
        floatingView = binding.root

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        setupDrag(params)
        setupInteractions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        // Clipboard auto-detection
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                val id = YouTubeParser.extractVideoId(text)
                if (id != null && !collectedUrls.contains(text)) {
                    collectedUrls.add(text)
                    updateBadge()
                    Toast.makeText(this, "Link #${collectedUrls.size} captured by lemezt!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        binding.layoutCompactBubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            expandDrawer()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupInteractions() {
        binding.btnFloatingCollapse.setOnClickListener {
            collapseBubble()
        }

        binding.btnOpenYouTube.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            }
        }

        binding.btnPasteLink.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                val id = YouTubeParser.extractVideoId(text)
                if (id != null) {
                    if (!collectedUrls.contains(text)) {
                        collectedUrls.add(text)
                        updateBadge()
                        Toast.makeText(this, "Added to list (#${collectedUrls.size})", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Link already added", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Clipboard does not contain a valid YouTube link", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDownloadAll.setOnClickListener {
            if (collectedUrls.isEmpty()) {
                Toast.makeText(this, "No links collected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val urlsToDownload = collectedUrls.toList()
            val repo = com.lemezt.app.data.repository.DownloadRepositoryImpl(applicationContext)

            scope.launch {
                for (url in urlsToDownload) {
                    val id = YouTubeParser.extractVideoId(url) ?: continue
                    val plan = OutputPlan(
                        mediaType = OutputMediaType.VIDEO,
                        container = "mp4",
                        qualityLabel = "720p",
                        estimatedBytes = null,
                        isProgressive = true,
                        embedCoverArt = true,
                        saveLyrics = false
                    )
                    repo.enqueueDownload(
                        mediaId = id,
                        sourceUrl = url,
                        title = "YouTube Video ($id)",
                        author = "YouTube",
                        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        plan = plan
                    )
                }
                TransferCoordinator.triggerQueue()
                Toast.makeText(applicationContext, "Enqueued ${urlsToDownload.size} downloads to lemezt!", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }

    private fun expandDrawer() {
        binding.layoutCompactBubble.visibility = View.GONE
        binding.layoutExpandedDrawer.visibility = View.VISIBLE
        updateBadge()
    }

    private fun collapseBubble() {
        binding.layoutExpandedDrawer.visibility = View.GONE
        binding.layoutCompactBubble.visibility = View.VISIBLE
    }

    private fun updateBadge() {
        val count = collectedUrls.size
        binding.tvCompactCount.text = "lemezt ($count)"
        binding.tvExpandedSummary.text = "$count links collected"
        binding.btnDownloadAll.text = "Download All ($count)"
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatingView?.let { windowManager?.removeView(it) }
    }
}
