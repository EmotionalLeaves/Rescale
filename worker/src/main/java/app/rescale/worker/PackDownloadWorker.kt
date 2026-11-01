package app.rescale.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.rescale.core.model.Packs
import app.rescale.engine.pipeline.PackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Download progress of one pack, as the Models screen sees it. */
sealed interface PackDownload {
    data object None : PackDownload
    data class Running(val bytes: Long, val total: Long) : PackDownload
    data class Failed(val message: String) : PackDownload
}

/**
 * Downloads one model pack in the background (resumable, SHA-256 verified). Only ever
 * started by the user tapping Download.
 */
class PackDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastNotify = 0L

    override suspend fun getForegroundInfo() = foreground(0, 0)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_PACK) ?: return Result.failure()
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        runCatching { setForeground(foreground(0, 0)) }
        return withContext(Dispatchers.IO) {
            try {
                PackStore(applicationContext).download(id, isCancelled = { isStopped }) { done, total ->
                    setProgressAsync(workDataOf(KEY_BYTES to done, KEY_TOTAL to total))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify > 1000) {
                        lastNotify = now
                        nm.notify(NOTIFICATION_ID, foreground(done, total).notification)
                    }
                }
                if (isStopped) Result.failure() else Result.success()
            } catch (e: Exception) {
                // Network hiccups: let WorkManager retry (the partial file resumes). Integrity errors: fail.
                if (e is java.io.IOException && runAttemptCount < 5) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
            }
        }
    }

    private fun foreground(done: Long, total: Long): ForegroundInfo {
        val name = Packs.all.find { it.id == inputData.getString(KEY_PACK) }?.name ?: "model pack"
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name")
            .setContentText(if (total > 0) "${done shr 20} of ${total shr 20} MB" else "Starting…")
            .setProgress(100, pct, total == 0L)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 43
        const val KEY_PACK = "pack"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private fun name(id: String) = "pack-$id"

        fun start(context: Context, id: String) {
            val req = OneTimeWorkRequestBuilder<PackDownloadWorker>()
                .setInputData(workDataOf(KEY_PACK to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, id: String) = WorkManager.getInstance(context).cancelUniqueWork(name(id))

        fun observe(context: Context, id: String): Flow<PackDownload> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(id)).map { infos ->
                val info = infos.lastOrNull() ?: return@map PackDownload.None
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> PackDownload.Running(0, 0)
                    WorkInfo.State.RUNNING -> PackDownload.Running(
                        info.progress.getLong(KEY_BYTES, 0), info.progress.getLong(KEY_TOTAL, 0),
                    )
                    WorkInfo.State.FAILED -> info.outputData.getString(KEY_ERROR)?.let { PackDownload.Failed(it) }
                        ?: PackDownload.None
                    else -> PackDownload.None
                }
            }
    }
}
