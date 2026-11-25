package com.sene.ventas

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Sincronización agrupada automática cada tres horas cuando hay conexión. */
class SeneBatchSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return if (PayrollRequestSync.syncAndWait(applicationContext, true, 8L * 60L * 1000L)) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}

object SeneSyncScheduler {
    private const val UNIQUE_WORK = "sene-batch-sync-every-3-hours"

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SeneBatchSyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
