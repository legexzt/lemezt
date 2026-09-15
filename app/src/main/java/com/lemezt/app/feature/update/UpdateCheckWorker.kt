package com.lemezt.app.feature.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lemezt.app.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("UpdateCheckWorker", "Starting periodic background update check...")
            val updateInfo = UpdateManager.fetchUpdateInfoDirect(context)
            if (updateInfo != null && updateInfo.hasUpdate) {
                Log.d("UpdateCheckWorker", "Update detected in background: ")
                NotificationHelper.showUpdateNotification(
                    context = context,
                    versionName = updateInfo.latestVersionName,
                    notes = updateInfo.releaseNotes,
                    downloadUrl = updateInfo.downloadUrl
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("UpdateCheckWorker", "Update check failed in worker: ")
            Result.retry()
        }
    }
}
