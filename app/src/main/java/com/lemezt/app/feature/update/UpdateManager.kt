package com.lemezt.app.feature.update

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.lemezt.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionName: String,
    val latestVersionCode: Long = 0L,
    val downloadUrl: String,
    val releaseNotes: String,
    val isForce: Boolean = false
)

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val KEY_LATEST_VERSION_CODE = "latest_version_code"
    private const val KEY_LATEST_VERSION_NAME = "latest_version_name"
    private const val KEY_UPDATE_URL = "update_url"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_FORCE_UPDATE = "force_update"

    // GitHub repository endpoint for zero-configuration auto updates
    private const val GITHUB_REPO = "legexzt/lemezt"
    private const val GITHUB_API_URL = "https://api.github.com/repos/legexzt/lemezt/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Direct update check runnable from both UI and Background Worker
     */
    suspend fun fetchUpdateInfoDirect(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        // 1. Check GitHub Releases API first (100% automated via gh releases)
        try {
            val req = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "lemezt-app")
                .build()

            val res = httpClient.newCall(req).execute()
            if (res.isSuccessful) {
                val bodyStr = res.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val rawTag = json.optString("tag_name", "").trim()
                    val tagName = rawTag.removePrefix("v").removePrefix("V")
                    val notes = json.optString("body", "What's new in this release.")
                    
                    var apkUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    if (!apkUrl.isNullOrBlank() && isNewerVersion(tagName, BuildConfig.VERSION_NAME)) {
                        Log.d(TAG, "GitHub release found: $tagName > ${BuildConfig.VERSION_NAME}")
                        return@withContext UpdateInfo(
                            hasUpdate = true,
                            latestVersionName = tagName,
                            downloadUrl = apkUrl,
                            releaseNotes = notes,
                            isForce = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub releases check skipped: ${e.message}")
        }

        // 2. Check Firebase Remote Config as secondary / enterprise channel
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            val configSettings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(60L)
                .build()
            remoteConfig.setConfigSettingsAsync(configSettings)
            remoteConfig.fetchAndActivate().await()

            val latestCode = remoteConfig.getLong(KEY_LATEST_VERSION_CODE)
            val latestName = remoteConfig.getString(KEY_LATEST_VERSION_NAME)
            val updateUrl = remoteConfig.getString(KEY_UPDATE_URL)
            val releaseNotes = remoteConfig.getString(KEY_RELEASE_NOTES)
            val isForce = remoteConfig.getBoolean(KEY_FORCE_UPDATE)

            if (latestCode > BuildConfig.VERSION_CODE.toLong() && updateUrl.isNotBlank()) {
                Log.d(TAG, "Firebase Remote Config update found: $latestName ($latestCode)")
                return@withContext UpdateInfo(
                    hasUpdate = true,
                    latestVersionName = if (latestName.isNotBlank()) latestName else "v$latestCode",
                    latestVersionCode = latestCode,
                    downloadUrl = updateUrl,
                    releaseNotes = releaseNotes,
                    isForce = isForce
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Remote Config check error: ${e.message}")
        }

        return@withContext null
    }

    fun checkForUpdate(activity: FragmentActivity, isManualCheck: Boolean = false) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val updateInfo = fetchUpdateInfoDirect(activity)

            withContext(Dispatchers.Main) {
                if (updateInfo != null && updateInfo.hasUpdate) {
                    showUpdateDialog(
                        activity,
                        updateInfo.latestVersionName,
                        updateInfo.downloadUrl,
                        updateInfo.releaseNotes,
                        updateInfo.isForce
                    )
                } else {
                    if (isManualCheck) {
                        Toast.makeText(
                            activity,
                            "Lemezt is up to date (v${BuildConfig.VERSION_NAME})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun showUpdateDialog(
        activity: FragmentActivity,
        latestName: String,
        updateUrl: String,
        notes: String,
        isForce: Boolean
    ) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("🚀 New Update Available!")
            .setMessage("Version $latestName is ready to install.\n\nWhat's New:\n$notes")
            .setPositiveButton("Update Now") { _, _ ->
                startDownloadAndInstall(activity, updateUrl, latestName)
            }
            .setCancelable(!isForce)

        if (!isForce) {
            dialog.setNegativeButton("Later", null)
        }

        dialog.show()
    }

    private fun startDownloadAndInstall(activity: FragmentActivity, url: String, versionName: String) {
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Downloading Update")
            setMessage("Downloading lemezt v$versionName...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val updatesDir = File(activity.cacheDir, "updates")
                if (!updatesDir.exists()) updatesDir.mkdirs()
                val apkFile = File(updatesDir, "lemezt_update.apk")
                if (apkFile.exists()) apkFile.delete()

                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("Empty response body")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                        withContext(Dispatchers.Main) {
                            progressDialog.progress = progress
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    triggerInstall(activity, apkFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun triggerInstall(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Cannot launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Compares semantic version strings like "1.0.1" vs "1.0.0"
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        try {
            val remoteParts = remote.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            val localParts = local.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
