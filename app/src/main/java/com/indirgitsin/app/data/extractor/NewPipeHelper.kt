package com.indirgitsin.app.data.extractor

import android.os.Build
import com.indirgitsin.app.data.model.StreamOption
import com.indirgitsin.app.data.model.StreamSelector
import com.indirgitsin.app.data.model.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod

object NewPipeHelper {
    private val initialization by lazy { NewPipe.init(DownloaderImpl.getInstance()) }
    fun ensureInitialized() { initialization }

    suspend fun extract(videoId: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val service = ServiceList.YouTube
            val extractor = service.getStreamExtractor(service.streamLHFactory.fromId(videoId))
            extractor.fetchPage()
            val streams = mutableListOf<StreamOption>()
            val live = extractor.streamType?.name == "LIVE_STREAM"
            if (live) {
                val hls = extractor.hlsUrl.orEmpty()
                if (hls.startsWith("https://") || hls.startsWith("http://")) {
                    streams += StreamOption("Canlı kayıt • MP4", "mp4", "live", hls, true, false, isLive = true)
                }
            }
            for (audio in if (live) emptyList() else extractor.audioStreams.orEmpty()) {
                if (!audio.isUrl || audio.deliveryMethod == DeliveryMethod.HLS) continue
                val url = audio.content?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                val ext = audio.getFormat()?.suffix?.lowercase() ?: continue
                val bitrate = audio.averageBitrate
                streams += StreamOption("Ses • ${ext.uppercase()} ${bitrate}kbps", ext,
                    "${bitrate}kbps", url, false, true, bitrate = bitrate, codec = audio.codec.orEmpty())
            }
            for (video in if (live) emptyList() else extractor.videoStreams.orEmpty() + extractor.videoOnlyStreams.orEmpty()) {
                if (!video.isUrl || video.deliveryMethod == DeliveryMethod.HLS) continue
                val url = video.content?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                val ext = video.getFormat()?.suffix?.lowercase() ?: continue
                val quality = video.resolution.orEmpty()
                streams += StreamOption("$quality • ${ext.uppercase()} • Video + Ses", ext, quality,
                    url, true, false, bitrate = video.bitrate, isVideoOnly = video.isVideoOnly,
                    codec = video.codec.orEmpty())
            }
            val prepared = StreamSelector.prepare(streams, Build.VERSION.SDK_INT)
            if (prepared.isEmpty()) return@withContext null
            VideoInfo(videoId, extractor.name, extractor.uploaderName,
                extractor.thumbnails.maxByOrNull { it.height }?.url ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                extractor.length, extractor.viewCount, "https://www.youtube.com/watch?v=$videoId", prepared, isLive = live,
                serviceName = "YouTube")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("NewPipeHelper", "Video çözümlenemedi: ${e.javaClass.simpleName}")
            null
        }
    }

    suspend fun extractUrl(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val service = try {
                NewPipe.getServiceByUrl(url)
            } catch (_: Exception) {
                ServiceList.all().firstOrNull {
                    try { it.streamLHFactory.acceptUrl(url) } catch (_: Exception) { false }
                }
            } ?: return@withContext null

            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()
            val streams = mutableListOf<StreamOption>()
            val live = extractor.streamType?.name == "LIVE_STREAM"
            if (live) {
                val hls = extractor.hlsUrl.orEmpty()
                if (hls.startsWith("https://") || hls.startsWith("http://")) {
                    streams += StreamOption("Canlı kayıt • MP4", "mp4", "live", hls, true, false, isLive = true)
                }
            }
            for (audio in if (live) emptyList() else extractor.audioStreams.orEmpty()) {
                if (!audio.isUrl || audio.deliveryMethod == DeliveryMethod.HLS) continue
                val contentUrl = audio.content?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                val ext = audio.getFormat()?.suffix?.lowercase() ?: continue
                val bitrate = audio.averageBitrate
                streams += StreamOption("Ses • ${ext.uppercase()} ${bitrate}kbps", ext,
                    "${bitrate}kbps", contentUrl, false, true, bitrate = bitrate, codec = audio.codec.orEmpty())
            }
            for (video in if (live) emptyList() else extractor.videoStreams.orEmpty() + extractor.videoOnlyStreams.orEmpty()) {
                if (!video.isUrl || video.deliveryMethod == DeliveryMethod.HLS) continue
                val contentUrl = video.content?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                val ext = video.getFormat()?.suffix?.lowercase() ?: continue
                val quality = video.resolution.orEmpty().ifBlank { "Standart" }
                streams += StreamOption("$quality • ${ext.uppercase()} • Video + Ses", ext, quality,
                    contentUrl, true, false, bitrate = video.bitrate, isVideoOnly = video.isVideoOnly,
                    codec = video.codec.orEmpty())
            }
            val prepared = StreamSelector.prepare(streams, Build.VERSION.SDK_INT)
            if (prepared.isEmpty()) return@withContext null
            val id = extractor.id?.ifBlank { null } ?: url.hashCode().toString()
            val thumb = extractor.thumbnails.orEmpty().maxByOrNull { it.height }?.url.orEmpty()
            val serviceName = service.serviceInfo.name
            VideoInfo(
                id = id,
                title = extractor.name.orEmpty().ifBlank { "Medya" },
                author = extractor.uploaderName.orEmpty(),
                thumbnailUrl = thumb,
                durationSeconds = extractor.length,
                viewCount = extractor.viewCount,
                url = url,
                streams = prepared,
                isLive = live,
                serviceName = serviceName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("NewPipeHelper", "URL çözümlenemedi ($url): ${e.javaClass.simpleName}")
            null
        }
    }
}
