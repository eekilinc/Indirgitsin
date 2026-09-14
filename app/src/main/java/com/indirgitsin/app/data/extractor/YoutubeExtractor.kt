package com.indirgitsin.app.data.extractor

import android.content.Context
import android.os.Build
import com.indirgitsin.app.data.model.StreamOption
import com.indirgitsin.app.data.model.StreamSelector
import com.indirgitsin.app.data.model.VideoInfo
import com.indirgitsin.app.util.YoutubeLinkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

object YoutubeExtractor {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private data class Cached(val video: VideoInfo, val createdAt: Long)
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 32
    }

    suspend fun extract(url: String, context: Context, forceRefresh: Boolean = false): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val isYoutube = YoutubeLinkHelper.isValidYoutubeUrl(url)
            val isSupported = isYoutube || YoutubeLinkHelper.isSupportedMediaUrl(url)
            if (!isSupported) {
                return@withContext Result.failure(IllegalArgumentException("Desteklenmeyen medya bağlantısı. YouTube, SoundCloud veya Bandcamp linki girin."))
            }

            val cacheKey = if (isYoutube) YoutubeLinkHelper.extractVideoId(url) ?: url else url
            synchronized(cache) {
                if (forceRefresh) cache.remove(cacheKey)
                cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.createdAt < 5 * 60_000 }?.let {
                    return@withContext Result.success(it.video)
                }
            }

            val extracted: VideoInfo?
            if (isYoutube) {
                val id = YoutubeLinkHelper.extractVideoId(url)
                    ?: return@withContext Result.failure(IllegalArgumentException("Geçerli bir YouTube video bağlantısı girin."))
                val direct = withTimeoutOrNull(30_000) { NewPipeHelper.extract(id) }
                currentCoroutineContext().ensureActive()
                // No video IDs are sent to public Piped/Invidious/Cobalt instances.
                extracted = direct ?: withTimeoutOrNull(60_000) { extractWeb(id, context.applicationContext) }
            } else {
                extracted = withTimeoutOrNull(45_000) { NewPipeHelper.extractUrl(url) }
            }

            val validVideo = extracted ?: error("İndirilebilir ses/video akışı bulunamadı. Bağlantıyı kontrol edin.")
            val result = validVideo.copy(streams = StreamSelector.withMp3Options(validVideo.streams))
            synchronized(cache) { cache[cacheKey] = Cached(result, System.currentTimeMillis()) }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun extractWeb(id: String, context: Context): VideoInfo? {
        ZemerCipherHelper.initialize(context)
        val watchUrl = "https://www.youtube.com/watch?v=$id&hl=en"
        val html = client.newCall(Request.Builder().url(watchUrl).header("User-Agent", "Mozilla/5.0").build()).awaitBody()
        val marker = Regex("ytInitialPlayerResponse\\s*=\\s*").find(html)
        var player = marker?.let { (JSONTokener(html.substring(it.range.last + 1)).nextValue() as? JSONObject) }
        if (player?.optJSONObject("streamingData") == null) {
            val clientVersion = Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
            val requestBody = JSONObject().apply {
                put("videoId", id)
                put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", clientVersion).put("hl", "en")))
                ZemerCipherHelper.signatureTimestamp()?.let { timestamp ->
                    put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", timestamp)))
                }
            }
            player = JSONObject(client.newCall(Request.Builder().url("https://www.youtube.com/youtubei/v1/player")
                .header("User-Agent", "Mozilla/5.0")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType())).build()).awaitBody())
        }
        val response = player ?: return null
        val details = response.optJSONObject("videoDetails") ?: return null
        val streaming = response.optJSONObject("streamingData") ?: return null
        val live = details.optBoolean("isLive") || response.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")?.optJSONObject("liveBroadcastDetails")?.optBoolean("isLiveNow") == true
        val streams = mutableListOf<StreamOption>()
        if (live) {
            val hls = streaming.optString("hlsManifestUrl")
            if (hls.startsWith("https://")) streams += StreamOption("Canlı kayıt • MP4", "mp4", "live", hls, true, false, isLive = true)
        }
        for (arrayName in listOf("formats", "adaptiveFormats")) {
            if (live) break
            val formats = streaming.optJSONArray(arrayName) ?: continue
            for (i in 0 until formats.length()) {
                currentCoroutineContext().ensureActive()
                val format = formats.getJSONObject(i)
                val mime = format.optString("mimeType")
                val audio = mime.startsWith("audio/")
                val video = mime.startsWith("video/")
                if (!audio && !video) continue
                val extension = when {
                    mime.contains("webm") -> "webm"
                    mime.contains("mp4") -> if (audio) "m4a" else "mp4"
                    else -> continue
                }
                val rawUrl = format.optString("url")
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                val resolved = ZemerCipherHelper.resolve(rawUrl, cipher, id) ?: continue
                val bitrate = format.optInt("bitrate") / 1000
                val quality = if (audio) "${bitrate}kbps" else format.optString("qualityLabel")
                val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty()
                streams += StreamOption(if (audio) "Ses • ${extension.uppercase()} $quality" else "$quality • ${extension.uppercase()} • Video + Ses",
                    extension, quality, resolved, video, audio, bitrate = bitrate,
                    isVideoOnly = video && arrayName == "adaptiveFormats", codec = codec)
            }
        }
        val prepared = StreamSelector.prepare(streams, Build.VERSION.SDK_INT)
        if (prepared.isEmpty()) return null
        val thumbnails = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnail = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
        return VideoInfo(id, details.optString("title", "Video"), details.optString("author"),
            thumbnail ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg", details.optString("lengthSeconds").toLongOrNull() ?: 0,
            details.optString("viewCount").toLongOrNull() ?: 0, watchUrl, prepared, isLive = live)
    }
}
