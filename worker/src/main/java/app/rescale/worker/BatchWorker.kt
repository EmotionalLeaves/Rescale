package app.rescale.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.rescale.core.model.AutoPlanner
import app.rescale.core.model.JobSettings
import app.rescale.core.model.Mode
import app.rescale.core.model.Stage
import app.rescale.core.model.Tuning
import app.rescale.engine.pipeline.Cancellable
import app.rescale.engine.pipeline.EtaEstimator
import app.rescale.engine.pipeline.ExportFormat
import app.rescale.engine.pipeline.ExportOptions
import app.rescale.engine.pipeline.Exporter
import app.rescale.engine.pipeline.FaceProbe
import app.rescale.engine.pipeline.Gallery
import app.rescale.engine.pipeline.ImageAnalyzer
import app.rescale.engine.pipeline.ImageLoader
import app.rescale.engine.pipeline.Throttle
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
import java.util.concurrent.atomic.AtomicReference

/** One row of the batch screen. */
data class BatchItem(
    val name: String,
    val index: Int,
    val state: State,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
) {
    enum class State { QUEUED, RUNNING, SAVED, FAILED, CANCELLED }
}

/**
 * Upscales one image of a batch with Auto settings (optionally a fixed scale), exports it with
 * the batch's export options and saves it to Pictures/Rescale. Always returns success (with a
 * status in the output) so one bad image doesn't cancel the rest of the chain.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val eta = EtaEstimator()
    private var lastNotify = 0L

    override suspend fun getForegroundInfo() = foreground(0, 0)

    override suspend fun doWork(): Result {
        val input = inputData.getString(KEY_INPUT)?.let(Uri::parse) ?: return done(false, "Missing input")
        val name = inputData.getString(KEY_NAME) ?: "image"
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Batch progress", NotificationManager.IMPORTANCE_LOW))
        }
        runCatching { setForeground(foreground(0, 0)) }

        val ctx = applicationContext
        val appVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val upscaler = Upscaler(ctx, appVersion)
        val work = File(ctx.cacheDir, "batch-work/$id").apply { mkdirs() }
        try {
            val info = runCatching { ImageLoader.info(ctx, input) }.getOrElse { return done(false, "Can't read image") }
            val available = Stage.entries.filter { it.packId in upscaler.packs.installed() }.toSet()
            val faces = FaceProbe.smallFaces(ctx, input, info, upscaler.packs)
            val auto = ImageAnalyzer.analyze(ctx, input, info)?.let { AutoPlanner.plan(it.copy(smallFaces = faces), available).settings }
                ?: JobSettings(Mode.PHOTO, 4, Tuning.defaultFor(Mode.PHOTO))
            val fixedScale = inputData.getInt(KEY_SCALE, 0)
            val settings = if (fixedScale > 0) auto.copy(scale = fixedScale) else auto

            val throttle = Throttle(ctx, upscaler.backends.gentle)
            eta.start()
            val canceller = AtomicReference<Cancellable?>(null)
            val outcome = coroutineScope {
                val watcher = launch { try { awaitCancellation() } finally { canceller.get()?.cancel() } }
                val r = withContext(Dispatchers.Default) {
                    upscaler.run(UpscaleRequest(input, settings), work, onStarted = { canceller.set(it) }) { d, t ->
                        setProgressAsync(workDataOf(KEY_DONE to d, KEY_TOTAL to t))
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastNotify > 1000) {
                            lastNotify = now
                            nm.notify(NOTIFICATION_ID, foreground(d, t).notification)
                        }
                        throttle.afterTile()
                    }
                }
                watcher.cancel()
                r
            }
            return when (outcome) {
                is UpscaleOutcome.Success -> {
                    val opts = exportOptions()
                    val stem = (Gallery.stem(name) ?: "Rescale") + "_${outcome.width}x${outcome.height}"
                    val e = Exporter.export(ctx, outcome.output, input, opts, File(work, "export"), stem)
                    Gallery.save(ctx, e)
                    done(true, e.note ?: "Saved as ${e.format.label}")
                }
                is UpscaleOutcome.Failed -> done(false, outcome.message)
                UpscaleOutcome.Cancelled -> done(false, "Cancelled")
            }
        } catch (e: Exception) {
            return done(false, e.message ?: "Failed")
        } finally {
            work.deleteRecursively()
            input.path?.let { File(it).delete() }
        }
    }

    private fun exportOptions() = ExportOptions(
        format = ExportFormat.entries.find { it.name == inputData.getString(KEY_FORMAT) } ?: ExportFormat.PNG,
        quality = inputData.getInt(KEY_QUALITY, 92),
        webpLossless = inputData.getBoolean(KEY_LOSSLESS, false),
        keepExif = inputData.getBoolean(KEY_EXIF, true),
    )

    private fun done(ok: Boolean, message: String) =
        Result.success(workDataOf(KEY_OK to ok, KEY_MESSAGE to message))

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val idx = inputData.getInt(KEY_INDEX, 0) + 1
        val count = inputData.getInt(KEY_COUNT, 1)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("Batch: image $idx of $count")
            .setContentText(if (total == 0) "Preparing…" else "Tile $done of $total")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel batch",
                // Cancelling one step of a chain also cancels everything queued after it.
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        const val UNIQUE = "batch"
        private const val CHANNEL = "batch"
        private const val NOTIFICATION_ID = 44
        private const val KEY_INPUT = "input"
        private const val KEY_NAME = "name"
        private const val KEY_INDEX = "index"
        private const val KEY_COUNT = "count"
        private const val KEY_SCALE = "scale"
        private const val KEY_FORMAT = "format"
        private const val KEY_QUALITY = "quality"
        private const val KEY_LOSSLESS = "lossless"
        private const val KEY_EXIF = "exif"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val KEY_OK = "ok"
        private const val KEY_MESSAGE = "message"
        private const val TAG_NAME = "name:"
        private const val TAG_INDEX = "idx:"

        /** [inputs]: local file URIs (already copied into app storage) with display names. */
        fun enqueue(
            context: Context,
            inputs: List<Pair<Uri, String>>,
            scale: Int,
            export: ExportOptions,
            onlyWhileCharging: Boolean,
        ) {
            if (inputs.isEmpty()) return
            val requests: List<OneTimeWorkRequest> = inputs.mapIndexed { i, (uri, name) ->
                OneTimeWorkRequestBuilder<BatchWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_INPUT to uri.toString(), KEY_NAME to name, KEY_INDEX to i, KEY_COUNT to inputs.size,
                            KEY_SCALE to scale, KEY_FORMAT to export.format.name, KEY_QUALITY to export.quality,
                            KEY_LOSSLESS to export.webpLossless, KEY_EXIF to export.keepExif,
                        ),
                    )
                    .setConstraints(Constraints.Builder().setRequiresCharging(onlyWhileCharging).build())
                    .addTag(TAG_NAME + name)
                    .addTag(TAG_INDEX + i)
                    .build()
            }
            var chain = WorkManager.getInstance(context)
                .beginUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first())
            for (r in requests.drop(1)) chain = chain.then(r)
            chain.enqueue()
        }

        fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)

        fun clearFinished(context: Context) = WorkManager.getInstance(context).pruneWork()

        fun observe(context: Context): Flow<List<BatchItem>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
                infos.map { info ->
                    val name = info.tags.firstOrNull { it.startsWith(TAG_NAME) }?.removePrefix(TAG_NAME) ?: "image"
                    val idx = info.tags.firstOrNull { it.startsWith(TAG_INDEX) }?.removePrefix(TAG_INDEX)?.toIntOrNull() ?: 0
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BatchItem(name, idx, BatchItem.State.QUEUED)
                        WorkInfo.State.RUNNING -> BatchItem(
                            name, idx, BatchItem.State.RUNNING,
                            info.progress.getInt(KEY_DONE, 0), info.progress.getInt(KEY_TOTAL, 0),
                        )
                        WorkInfo.State.SUCCEEDED -> BatchItem(
                            name, idx,
                            if (info.outputData.getBoolean(KEY_OK, false)) BatchItem.State.SAVED else BatchItem.State.FAILED,
                            message = info.outputData.getString(KEY_MESSAGE),
                        )
                        WorkInfo.State.FAILED -> BatchItem(name, idx, BatchItem.State.FAILED, message = "Failed")
                        WorkInfo.State.CANCELLED -> BatchItem(name, idx, BatchItem.State.CANCELLED)
                    }
                }.sortedBy { it.index }
            }
    }
}
