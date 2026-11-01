package app.rescale.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.rescale.core.model.Mode
import app.rescale.engine.pipeline.Cancellable
import app.rescale.engine.pipeline.UpscaleOutcome
import app.rescale.engine.pipeline.UpscaleRequest
import app.rescale.engine.pipeline.Upscaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** UI-facing view of the single active/last job. */
sealed interface JobState {
    data object Idle : JobState
    data class Running(val done: Int, val total: Int, val etaSeconds: Long?) : JobState
    data class Done(
        val output: File,
        val preview: File,
        val width: Int,
        val height: Int,
        val backend: String,
        val millis: Long,
        val input: Uri?,
    ) : JobState
    data class Failed(val message: String) : JobState
    data object Cancelled : JobState
}

class UpscaleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var lastNotify = 0L
    private var startedAt = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, null)

    override suspend fun doWork(): Result {
        val input = inputData.getString(KEY_INPUT)?.let(Uri::parse) ?: return Result.failure()
        val mode = Mode.valueOf(inputData.getString(KEY_MODE) ?: Mode.PHOTO.name)
        val scale = inputData.getInt(KEY_SCALE, 4)
        val outDir = resultDir(applicationContext, id)

        ensureChannel()
        runCatching { setForeground(foregroundInfo(0, 0, null)) }

        val appVersion = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        val upscaler = Upscaler(applicationContext, appVersion)
        val canceller = AtomicReference<Cancellable?>(null)
        startedAt = SystemClock.elapsedRealtime()

        val outcome = coroutineScope {
            // If WorkManager stops us (user tapped Cancel), flip the native cancel flag.
            val watcher = launch {
                try { awaitCancellation() } finally { canceller.get()?.cancel() }
            }
            val r = withContext(Dispatchers.Default) {
                upscaler.run(
                    UpscaleRequest(input, mode, scale),
                    outDir,
                    onStarted = { canceller.set(it) },
                    onProgress = { d, t -> reportProgress(d, t) },
                )
            }
            canceller.set(null)
            watcher.cancel()
            r
        }

        return when (outcome) {
            is UpscaleOutcome.Success -> Result.success(
                workDataOf(
                    KEY_OUTPUT to outcome.output.path,
                    KEY_PREVIEW to outcome.preview.path,
                    KEY_WIDTH to outcome.width,
                    KEY_HEIGHT to outcome.height,
                    KEY_BACKEND to outcome.backend.name,
                    KEY_MILLIS to outcome.millis,
                    KEY_INPUT to input.toString(),
                ),
            )
            is UpscaleOutcome.Failed -> Result.failure(workDataOf(KEY_ERROR to outcome.message))
            UpscaleOutcome.Cancelled -> Result.failure(workDataOf(KEY_CANCELLED to true))
        }
    }

    private fun reportProgress(done: Int, total: Int) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val eta = if (done > 0) (elapsed * (total - done) / done) / 1000 else null
        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_ETA to (eta ?: -1L)))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify > 700 || done == total) {
            lastNotify = now
            notifications.notify(NOTIFICATION_ID, buildNotification(done, total, eta))
        }
    }

    private fun foregroundInfo(done: Int, total: Int, eta: Long?): ForegroundInfo {
        val n = buildNotification(done, total, eta)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(done: Int, total: Int, eta: Long?) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("Upscaling")
            .setContentText(
                if (total == 0) "Preparing…"
                else "Tile $done of $total" + (eta?.let { " · ${formatEta(it)} left" } ?: ""),
            )
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Upscaling progress", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val UNIQUE_NAME = "upscale"
        private const val CHANNEL_ID = "progress"
        private const val NOTIFICATION_ID = 42

        const val KEY_INPUT = "input"
        const val KEY_MODE = "mode"
        const val KEY_SCALE = "scale"
        const val KEY_OUTPUT = "output"
        const val KEY_PREVIEW = "preview"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_BACKEND = "backend"
        const val KEY_MILLIS = "millis"
        const val KEY_ERROR = "error"
        const val KEY_CANCELLED = "cancelled"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ETA = "eta"

        fun resultsRoot(context: Context) = File(context.filesDir, "results")
        fun resultDir(context: Context, id: UUID) = File(resultsRoot(context), id.toString())

        fun formatEta(seconds: Long): String =
            if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"

        fun enqueue(context: Context, input: Uri, mode: Mode, scale: Int, onlyWhileCharging: Boolean = false) {
            // One job at a time (batch queue comes later): clear old results first.
            resultsRoot(context).deleteRecursively()
            val request = OneTimeWorkRequestBuilder<UpscaleWorker>()
                .setInputData(workDataOf(KEY_INPUT to input.toString(), KEY_MODE to mode.name, KEY_SCALE to scale))
                .setConstraints(Constraints.Builder().setRequiresCharging(onlyWhileCharging).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        fun clear(context: Context) {
            WorkManager.getInstance(context).pruneWork()
            resultsRoot(context).deleteRecursively()
        }

        fun observe(context: Context): Flow<JobState> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).map { infos ->
                val info = infos.lastOrNull() ?: return@map JobState.Idle
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> JobState.Running(0, 0, null)
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val eta = p.getLong(KEY_ETA, -1L)
                        JobState.Running(p.getInt(KEY_DONE, 0), p.getInt(KEY_TOTAL, 0), eta.takeIf { it >= 0 })
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val d = info.outputData
                        val out = d.getString(KEY_OUTPUT)?.let(::File)
                        val prev = d.getString(KEY_PREVIEW)?.let(::File)
                        if (out == null || prev == null || !out.exists()) JobState.Idle
                        else JobState.Done(
                            out, prev, d.getInt(KEY_WIDTH, 0), d.getInt(KEY_HEIGHT, 0),
                            d.getString(KEY_BACKEND) ?: "", d.getLong(KEY_MILLIS, 0),
                            d.getString(KEY_INPUT)?.let(Uri::parse),
                        )
                    }
                    WorkInfo.State.FAILED ->
                        if (info.outputData.getBoolean(KEY_CANCELLED, false)) JobState.Cancelled
                        else JobState.Failed(info.outputData.getString(KEY_ERROR) ?: "Something went wrong")
                    WorkInfo.State.CANCELLED -> JobState.Cancelled
                }
            }
    }
}
