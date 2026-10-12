package dev.ohmysync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.ohmysync.data.SyncTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)
    private fun constraints(task: SyncTask) = Constraints.Builder()
        .setRequiredNetworkType(if (task.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(task.chargingOnly).setRequiresBatteryNotLow(true).build()
    fun schedule(task: SyncTask) {
        if (!task.enabled) { cancel(task.id); return }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(task.intervalMinutes, TimeUnit.MINUTES)
            .setInitialDelay(task.intervalMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf("taskId" to task.id)).setConstraints(constraints(task))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("sync").addTag(task.id).build()
        manager.enqueueUniquePeriodicWork("periodic-${task.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun now(task: SyncTask) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("taskId" to task.id))
            .setConstraints(constraints(task)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("sync").addTag(task.id).addTag("manual").build()
        manager.enqueueUniqueWork("manual-${task.id}", ExistingWorkPolicy.KEEP, request)
    }
    fun cancel(id: String) { manager.cancelAllWorkByTag(id) }
    fun cancelAll() { manager.cancelAllWorkByTag("sync") }
    suspend fun cancelAndWait(id: String) = withContext(Dispatchers.IO) { manager.cancelAllWorkByTag(id).result.get() }
    suspend fun cancelAllAndWait() = withContext(Dispatchers.IO) { manager.cancelAllWorkByTag("sync").result.get() }
}
