package com.indirgitsin.app.data.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.Data
import com.indirgitsin.app.MainActivity
import com.indirgitsin.app.data.SettingsStore
import com.indirgitsin.app.data.extractor.YoutubeExtractor
import com.indirgitsin.app.data.model.StreamSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    companion object {
        private val slots = Semaphore(2)
        private const val CHANNEL = "media_downloads"
    }
    private val title = inputData.getString("title") ?: "Video"
    private val resumeId = inputData.getString("resumeId")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() } ?: id
    private val directory get() = File(applicationContext.noBackupFilesDir, "downloads/$resumeId")
    private val notificationId = id.hashCode()
    private var recordingNow = false
    private val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createChannel()
        setProgress(workDataOf("name" to title, "stage" to "Sırada"))
        try {
            DownloadQueueCoordinator.withPermit(id) {
                setProgress(workDataOf("name" to title, "stage" to "Bağlantı çözümleniyor…"))
                setForeground(foreground("Bağlantı çözümleniyor…", 0))
                performDownload()
            }
        } catch (e: CancellationException) {
            // Before API 31 WorkManager cannot expose an OS stop reason. Query persistent state
            // outside the cancelled coroutine so an explicit cancel can still release its files.
            val cancelledByUser = if (Build.VERSION.SDK_INT >= 31) {
                stopReason == androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP
            } else withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching {
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id)
                        .get(3, java.util.concurrent.TimeUnit.SECONDS)?.state == androidx.work.WorkInfo.State.CANCELLED
                }.getOrDefault(false)
            }
            if (cancelledByUser) directory.deleteRecursively()
            throw e
        } catch (e: Exception) {
            val reason = when (e) {
                is StreamHttpException -> "Medya bağlantısı reddedildi (HTTP ${e.status}). Yeniden deneyin."
                is IOException -> "Ağ veya dosya hatası: ${e.message.orEmpty().take(160)}"
                else -> e.message.orEmpty().take(180).ifBlank { "İndirme tamamlanamadı." }
            }
            if (e is IOException && !inputData.getBoolean("isLive", false) && !File(directory, "live.media").exists() && runAttemptCount < 2) {
                setProgress(workDataOf("name" to title, "stage" to "Yeniden denenecek"))
                Result.retry()
            } else {
                showResult("İndirme başarısız", reason)
                Result.failure(Data.Builder().putAll(inputData).putString("resumeId", resumeId.toString()).putBoolean("isLive", inputData.getBoolean("isLive", false) || File(directory, "live.media").exists()).putString("name", title).putString("error", reason).build())
            }
        }
    }

    private suspend fun performDownload(): Result {
        if (inputData.getBoolean("isLive", false)) return performLiveDownload()
        val videoId = inputData.getString("videoId")
        val sourceUrl = inputData.getString("sourceUrl")?.takeIf { it.isNotBlank() }
            ?: (if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else error("İndirme hedef URL'si bulunamadı."))
        val info = YoutubeExtractor.extract(sourceUrl, applicationContext, forceRefresh = runAttemptCount > 0 || inputData.getBoolean("manualRetry", false)).getOrThrow()
        val candidates = info.streams.filter {
            it.isDownloadable && it.extension == inputData.getString("extension") &&
                it.quality == inputData.getString("quality") && it.isAudioOnly == inputData.getBoolean("audioOnly", false)
        }
        val option = (if (inputData.getBoolean("autoSelect", false)) {
            StreamSelector.preferred(info.streams, inputData.getBoolean("highQuality", true), inputData.getString("audioFormat") ?: "M4A")
        } else candidates.firstOrNull { it.codec == inputData.getString("codec") && it.isVideoOnly == inputData.getBoolean("videoOnly", false) }
            ?: candidates.firstOrNull()) ?: error("Seçilen sesli kalite artık mevcut değil. Videoyu tekrar açıp kalite seçin.")
        if (option.isLive) return performLiveDownload(option.url)
        val extension = option.extension
        val safeTitle = title.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.').take(70).ifBlank { "video" }
        val quality = option.quality.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
        val name = "${safeTitle}_${quality}_${id.toString().take(8)}.$extension"
        // Keep active files away from the user-clearable thumbnail/cache directory.
        check(directory.isDirectory || directory.mkdirs()) { "Geçici indirme klasörü oluşturulamadı." }
        directory.setLastModified(System.currentTimeMillis())
        val primary = File(directory, "primary.part")
        val audio = File(directory, "audio.part")
        val output = File(directory, "output.$extension")
        val primaryBytes = AtomicLong()
        val audioBytes = AtomicLong()
        val primaryTotal = AtomicLong(-1)
        val audioTotal = AtomicLong(if (option.needsMuxing) -1 else 0)
        val primaryInitial = AtomicLong(-1)
        val audioInitial = AtomicLong(if (option.needsMuxing) -1 else 0)
        val lastUpdate = AtomicLong()
        val estimator = TransferProgress(SystemClock.elapsedRealtime())
        fun progress() {
            val now = SystemClock.elapsedRealtime()
            val previous = lastUpdate.get()
            if (now - previous < 600 || !lastUpdate.compareAndSet(previous, now)) return
            val downloaded = primaryBytes.get() + audioBytes.get()
            val total = if (primaryTotal.get() < 0 || audioTotal.get() < 0) -1 else primaryTotal.get() + audioTotal.get()
            val percent = if (total > 0) (downloaded * 95 / total).toInt().coerceIn(0, 95) else 0
            val resumed = primaryInitial.get().coerceAtLeast(0) + audioInitial.get().coerceAtLeast(0)
            val estimate = estimator.update(now, (downloaded - resumed).coerceAtLeast(0), if (total < 0) -1 else total - resumed)
            setProgressAsync(workDataOf("name" to name, "stage" to "İndiriliyor", "bytes" to downloaded,
                "total" to total, "percent" to percent, "speed" to estimate.bytesPerSecond, "eta" to (estimate.remainingSeconds ?: -1)))
            val speed = android.text.format.Formatter.formatShortFileSize(applicationContext, estimate.bytesPerSecond)
            notifySafely(notificationId, notification("İndiriliyor • $speed/sn", percent))
        }
        fun streamProgress(done: Long, total: Long, bytes: AtomicLong, length: AtomicLong, initial: AtomicLong) {
            initial.updateAndGet { if (it < 0 || done < it) done else it }
            bytes.set(done); length.set(total); progress()
        }
        try {
            val cover = coroutineScope {
                val ticker = launch { while (true) { delay(1_000); progress() } }
                try {
                    val coverJob = if (option.isAudioOnly) async { MediaArtwork.fetch(info.thumbnailUrl) } else null
                    val videoJob = async {
                        MediaTransfer.download(option.url, primary, "$videoId|${option.extension}|${option.quality}|${option.codec}") { done, total -> streamProgress(done, total, primaryBytes, primaryTotal, primaryInitial) }
                    }
                    val audioJob = if (option.needsMuxing) async {
                        MediaTransfer.download(requireNotNull(option.audioUrl), audio, "$videoId|audio|${option.audioCodec}") { done, total -> streamProgress(done, total, audioBytes, audioTotal, audioInitial) }
                    } else null
                    videoJob.await()
                    audioJob?.await()
                    coverJob?.await()
                } finally { ticker.cancel() }
            }
            currentCoroutineContext().ensureActive()
            val resultFile = if (option.convertToMp3) {
                setForeground(foreground("MP3'e dönüştürülüyor", 95))
                var lastConversionUpdate = 0L
                AudioMp3Converter.convert(primary, output, option.bitrate, Mp3Tags.create(info.title, info.author, cover)) { percent ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastConversionUpdate >= 600 || percent == 100) {
                        lastConversionUpdate = now
                        setProgressAsync(workDataOf("name" to name, "stage" to "MP3'e dönüştürülüyor • %$percent", "percent" to (95 + percent * 3 / 100)))
                    }
                }
                output
            } else if (option.needsMuxing) {
                setProgress(workDataOf("name" to name, "stage" to "Video ve ses birleştiriliyor", "percent" to 96))
                setForeground(foreground("Video ve ses birleştiriliyor", 96))
                MediaFileMuxer.mux(primary, audio, output, extension)
                output
            } else primary
            MediaFileMuxer.validate(resultFile, option.isVideo, info.durationSeconds)
            currentCoroutineContext().ensureActive()
            setProgress(workDataOf("name" to name, "stage" to "Dosya kaydediliyor", "percent" to 99))
            val folder = inputData.getString("folder") ?: SettingsStore.getDownloadSubfolderNow(applicationContext)
            val mime = DownloadStorage.mime(extension, option.isAudioOnly)
            val uri = DownloadStorage.publish(applicationContext, resultFile, name, mime, folder)
            if (option.isAudioOnly) MediaArtwork.save(applicationContext, uri, MediaArtwork.Entry(info.title, info.author, cover))
            val completedSize = resultFile.length()
            directory.deleteRecursively()
            showResult("İndirme tamamlandı", name)
            return Result.success(workDataOf("name" to name, "uri" to uri.toString(), "mime" to mime,
                "size" to completedSize, "completedAt" to System.currentTimeMillis()))
        } finally {
            // Preserve checked input ranges for WorkManager and manual retries, never a half-muxed output.
            output.delete()
        }
    }

    private suspend fun performLiveDownload(manifestUrl: String? = null): Result {
        check(directory.isDirectory || directory.mkdirs())
        directory.setLastModified(System.currentTimeMillis())
        val safeTitle = title.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.').take(70).ifBlank { "live" }
        val name = "${safeTitle}_live_${id.toString().take(8)}.mp4"
        val output = File(directory, "output.mp4")
        try {
            val existing = HlsRecorder.recover(directory)
            val recording = existing ?: run {
                val videoId = inputData.getString("videoId")
                val sourceUrl = inputData.getString("sourceUrl")?.takeIf { it.isNotBlank() }
                    ?: (if (!videoId.isNullOrBlank()) "https://www.youtube.com/watch?v=$videoId" else error("Yayın adresi bulunamadı."))
                val url = manifestUrl ?: YoutubeExtractor.extract(sourceUrl,
                    applicationContext, forceRefresh = true).getOrThrow().streams.firstOrNull { it.isLive }?.url
                    ?: error("Bu yayın şu anda desteklenen canlı HLS akışını sunmuyor.")
                recordingNow = true
                setForeground(foreground("Canlı yayın kaydediliyor", 0))
                setProgress(workDataOf("name" to name, "stage" to "Canlı yayın kaydediliyor", "recording" to true))
                HlsRecorder.record(url, directory, inputData.getInt("recordMinutes", 15).coerceIn(1, 60)) { bytes, seconds ->
                    val stage = "Canlı kayıt • ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                    setProgressAsync(workDataOf("name" to name, "stage" to stage, "bytes" to bytes, "total" to -1L,
                        "recording" to true, "seconds" to seconds))
                    notifySafely(notificationId, notification(stage, 0))
                }
            }
            recordingNow = false
            setForeground(foreground("Canlı kayıt MP4 olarak hazırlanıyor", 96))
            setProgress(workDataOf("name" to name, "stage" to "Canlı kayıt MP4 olarak hazırlanıyor", "percent" to 96))
            MediaFileMuxer.remuxCapture(recording.source, output)
            MediaFileMuxer.validate(output, true, recording.seconds)
            val uri = DownloadStorage.publish(applicationContext, output, name, "video/mp4",
                inputData.getString("folder") ?: SettingsStore.getDownloadSubfolderNow(applicationContext))
            val size = output.length()
            directory.deleteRecursively()
            showResult(recording.reason, name)
            return Result.success(workDataOf("name" to name, "uri" to uri.toString(), "mime" to "video/mp4",
                "size" to size, "completedAt" to System.currentTimeMillis(), "note" to recording.reason))
        } finally { recordingNow = false; output.delete() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
    }
    private fun foreground(stage: String, percent: Int): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification(stage, percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification(stage, percent))

    private fun notification(stage: String, percent: Int): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(stage)
            .setProgress(100, percent, percent == 0).setOngoing(true).setOnlyAlertOnce(true)
            .apply {
                if (recordingNow) addAction(android.R.drawable.ic_media_pause, "Durdur ve kaydet",
                    PendingIntent.getBroadcast(applicationContext, notificationId,
                        Intent(applicationContext, LiveRecordingReceiver::class.java).putExtra("resumeId", resumeId.toString()),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "İptal", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()

    private fun showResult(heading: String, message: String) {
        val open = PendingIntent.getActivity(applicationContext, notificationId, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifySafely(notificationId xor Int.MIN_VALUE, NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(heading).setContentText(message)
            .setContentIntent(open).setAutoCancel(true).build())
    }
    private fun notifySafely(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { manager.notify(id, notification) } catch (_: SecurityException) { /* Progress remains visible in-app. */ }
    }
}
