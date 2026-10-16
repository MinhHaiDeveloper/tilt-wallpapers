package com.tilt.wallpapers.wallpaper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tilt.wallpapers.data.Repo
import java.util.concurrent.TimeUnit

class ScheduledRotationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Backup only: the wallpaper itself switches on the minute whenever it is visible.
        Repo.init(applicationContext)
        Repo.applySchedule()
        return Result.success()
    }
    
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledRotationWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "scheduled_rotation",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
        
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("scheduled_rotation")
        }
    }
}
