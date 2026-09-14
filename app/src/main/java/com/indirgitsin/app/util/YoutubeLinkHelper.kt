package com.indirgitsin.app.util

import java.net.URI
import java.net.URLDecoder

object YoutubeLinkHelper {
    private val videoIdPattern = Regex("[a-zA-Z0-9_-]{11}")
    private val playlistIdPattern = Regex("[a-zA-Z0-9_-]+")
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "www.music.youtube.com", "youtu.be")

    private fun parse(url: String?): URI? = try {
        val uri = URI(url?.trim() ?: return null)
        uri.takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.host?.lowercase() in hosts && it.userInfo == null }
    } catch (_: Exception) { null }

    private fun parameter(uri: URI, key: String): String? = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
        val pair = part.split('=', limit = 2)
        if (pair.size == 2 && pair[0] == key) URLDecoder.decode(pair[1], "UTF-8") else null
    }

    fun extractVideoId(url: String?): String? {
        val uri = parse(url) ?: return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        val candidate = when {
            uri.host.equals("youtu.be", true) -> segments.firstOrNull()
            segments.firstOrNull() in setOf("shorts", "embed", "live") -> segments.getOrNull(1)
            uri.path == "/watch" -> parameter(uri, "v")
            else -> null
        }
        return candidate?.takeIf { videoIdPattern.matches(it) }
    }

    fun extractPlaylistId(url: String?): String? = parse(url)?.let { parameter(it, "list") }?.takeIf { playlistIdPattern.matches(it) }
    fun isPlaylistUrl(url: String?): Boolean = extractPlaylistId(url) != null
    fun isValidYoutubeUrl(url: String?): Boolean = extractVideoId(url) != null || isPlaylistUrl(url)
    fun normalizeUrl(url: String): String = extractVideoId(url)?.let { "https://www.youtube.com/watch?v=$it" } ?: url

    fun findYoutubeUrlInText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("https?://[^\\s<>]+").findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '!', ';') }
            .firstOrNull { isValidYoutubeUrl(it) }
    }

    fun isSoundCloudUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = try { URI(url.trim()) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return (host == "soundcloud.com" || host.endsWith(".soundcloud.com") || host == "on.soundcloud.com") &&
            !u.path.isNullOrBlank() && u.path.trim('/') != ""
    }

    fun isBandcampUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = try { URI(url.trim()) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return (host.endsWith(".bandcamp.com") || host == "bandcamp.com") &&
            !u.path.isNullOrBlank() && (u.path.contains("/track/") || u.path.contains("/album/"))
    }

    fun isMediaCccUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = try { URI(url.trim()) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return host == "media.ccc.de" && !u.path.isNullOrBlank()
    }

    fun detectPlatform(url: String?): String? {
        return when {
            isValidYoutubeUrl(url) -> "YouTube"
            isSoundCloudUrl(url) -> "SoundCloud"
            isBandcampUrl(url) -> "Bandcamp"
            isMediaCccUrl(url) -> "MediaCCC"
            else -> null
        }
    }

    fun isSupportedMediaUrl(url: String?): Boolean = detectPlatform(url) != null

    fun findMediaUrlInText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("https?://[^\\s<>]+").findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '!', ';') }
            .firstOrNull { isSupportedMediaUrl(it) }
    }

    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
