package com.lemezt.app.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ImageLoader {

    private val memoryCache: LruCache<String, Bitmap>
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8 // 1/8th of available RAM
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun load(url: String?, imageView: ImageView, cornerRadiusDp: Float = 8f) {
        if (url.isNullOrBlank()) return

        val cached = memoryCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.tag = url

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()
                    val response = client.newCall(request).execute()
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (rawBmp != null && cornerRadiusDp > 0f) {
                            getRoundedCornerBitmap(rawBmp, cornerRadiusDp * imageView.resources.displayMetrics.density)
                        } else {
                            rawBmp
                        }
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, pixels: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val color = -0xbdbdbe
        val paint = Paint()
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = color
        canvas.drawRoundRect(rectF, pixels, pixels, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }
}
