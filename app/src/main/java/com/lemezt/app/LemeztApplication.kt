package com.lemezt.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.lemezt.app.feature.update.UpdateCheckWorker
import com.lemezt.app.util.NotificationHelper
import java.util.concurrent.TimeUnit

class LemeztApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            NotificationHelper.createNotificationChannel(this)
            schedulePeriodicUpdateCheck()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun schedulePeriodicUpdateCheck() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val updateWorkRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "LemeztUpdateCheckWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                updateWorkRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
