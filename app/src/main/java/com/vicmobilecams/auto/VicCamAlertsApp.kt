package com.vicmobilecams.auto

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class VicCamAlertsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleDailyCameraUpdates()
    }

    private fun scheduleDailyCameraUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workManager = WorkManager.getInstance(this)

        // Runs once immediately so a fresh install doesn't wait a full day for its first sync.
        workManager.enqueueUniqueWork(
            "cameras-initial-sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CamerasUpdateWorker>()
                .setConstraints(constraints)
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            "cameras-daily-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CamerasUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build(),
        )
    }
}
