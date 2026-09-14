package com.indirgitsin.app.data.model

data class VideoInfo(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val viewCount: Long,
    val url: String,
    val streams: List<StreamOption> = emptyList(),
    val isLive: Boolean = false,
    val serviceName: String = "YouTube"
)

data class StreamOption(
    val label: String, // örn: "1080p MP4", "720p", "MP3 128kbps"
    val extension: String, // mp4, mp3, m4a, webm
    val quality: String,
    val url: String,
    val isVideo: Boolean,
    val isAudioOnly: Boolean,
    val sizeApprox: String = "",
    val bitrate: Int = 0,
    val audioUrl: String? = null,
    val isVideoOnly: Boolean = false,
    val codec: String = "",
    val audioCodec: String = "",
    val convertToMp3: Boolean = false,
    val isLive: Boolean = false,
    val recordMinutes: Int = 15
) {
    val needsMuxing: Boolean get() = isVideoOnly && !audioUrl.isNullOrBlank()
    val hasAudio: Boolean get() = isAudioOnly || (isVideo && (!isVideoOnly || needsMuxing))
    val isDownloadable: Boolean get() = extension in setOf("mp4", "webm", "m4a", "mp3", "opus", "ogg") && hasAudio
}

data class PlaylistVideo(
    val id: String,
    val title: String,
    val thumbnailUrl: String = "",
    val durationSeconds: Long = 0L
)

data class PlaylistInfo(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val videos: List<PlaylistVideo>,
    val videoCount: Int = 0
)

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val video: VideoInfo) : UiState()
    data class PlaylistSuccess(val playlist: PlaylistInfo) : UiState()
    data class Error(val message: String) : UiState()
}
