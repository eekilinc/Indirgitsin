package com.indirgitsin.app.data.downloader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import com.indirgitsin.app.data.SettingsStore
import com.indirgitsin.app.data.model.StreamOption
import com.indirgitsin.app.data.model.VideoInfo
import com.indirgitsin.app.data.model.PlaylistVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object FileDownloader {
    const val TAG = "media_download"
    internal val enqueueLock = Mutex()

    // Persist stable selectors only: CDN URLs expire and may exceed WorkManager's Data limit.
    suspend fun enqueue(context: Context, video: VideoInfo, option: StreamOption): Boolean {
        require(option.isDownloadable) { "Bu akış sesli video olarak indirilemiyor. Başka bir kalite seçin." }
        return enqueueData(context, workDataOf("videoId" to video.id, "sourceUrl" to video.url, "title" to video.title.take(180),
            "quality" to option.quality, "extension" to option.extension, "audioOnly" to option.isAudioOnly,
            "codec" to option.codec, "videoOnly" to option.isVideoOnly, "convertToMp3" to option.convertToMp3,
            "mp3Bitrate" to option.bitrate, "isLive" to option.isLive, "recordMinutes" to option.recordMinutes.coerceIn(1, 60)))
    }

    suspend fun prioritize(id: UUID) {
        DownloadQueueCoordinator.prioritize(id)
    }

    fun cancel(context: Context, id: UUID) {
        WorkManager.getInstance(context.applicationContext).cancelWorkById(id)
    }

    suspend fun retry(context: Context, job: WorkInfo): Boolean {
        require(job.state == WorkInfo.State.FAILED && (!job.outputData.getString("videoId").isNullOrBlank() || !job.outputData.getString("sourceUrl").isNullOrBlank())) {
            "Bu eski indirme için videoyu yeniden açıp kalite seçin."
        }
        val data = Data.Builder().putAll(job.outputData).putBoolean("manualRetry", true)
        if (job.outputData.getBoolean("isLive", false)) {
            val old = job.outputData.getString("resumeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (old != null && !hasRecording(context, old.toString())) data.putString("resumeId", UUID.randomUUID().toString())
        }
        return enqueueData(context, data.build())
    }

    suspend fun enqueuePlaylist(context: Context, videos: List<PlaylistVideo>, highQuality: Boolean, audioFormat: String): Int {
        var added = 0
        for (video in videos.distinctBy { it.id }) {
            if (enqueueData(context, workDataOf("videoId" to video.id, "title" to video.title.take(180),
                    "autoSelect" to true, "highQuality" to highQuality, "audioFormat" to audioFormat))) added++
        }
        return added
    }

    private suspend fun enqueueData(context: Context, source: Data): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT <= 28) check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "İndirmek için depolama izni gerekiyor."
        }
        val unmetered = SettingsStore.unmeteredFlow(context).first()
        val data = Data.Builder().putAll(source)
            .putString("resumeId", source.getString("resumeId")?.let { UUID.fromString(it).toString() } ?: UUID.randomUUID().toString())
            .putBoolean("unmetered", unmetered)
            .putString("folder", SettingsStore.downloadSubfolderFlow(context).first()).build()
        val selectors = listOf("videoId", "sourceUrl", "autoSelect", "highQuality", "audioFormat", "quality", "extension", "audioOnly", "codec", "videoOnly", "convertToMp3", "isLive", "recordMinutes")
        val identity = selectors.joinToString("|") { key -> "$key=${data.keyValueMap[key]}" }
        val key = TAG + ":" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val manager = WorkManager.getInstance(context.applicationContext)
        enqueueLock.withLock {
            if (manager.getWorkInfosForUniqueWork(key).get().any { !it.state.isFinished }) return@withLock false
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data).addTag(TAG).addTag("title:${data.getString("title").orEmpty().take(180)}")
                .addTag("resume:${data.getString("resumeId")}")
                .addTag(if (data.getBoolean("isLive", false)) "mode:live" else "mode:download")
                .addTag(if (unmetered) "network:unmetered" else "network:connected")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(
                    if (data.getBoolean("isLive", false) && data.getBoolean("manualRetry", false) &&
                        hasRecording(context, requireNotNull(data.getString("resumeId")))) NetworkType.NOT_REQUIRED
                    else if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request).result.get()
            true
        }
    }

    private fun hasRecording(context: Context, resumeId: String): Boolean =
        listOf("live.properties", "live.properties.bak").any { java.io.File(context.noBackupFilesDir, "downloads/$resumeId/$it").isFile }
}
