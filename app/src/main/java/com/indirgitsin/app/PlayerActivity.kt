package com.indirgitsin.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.indirgitsin.app.data.SettingsStore
import com.indirgitsin.app.data.lang.LocalAppLanguage
import com.indirgitsin.app.ui.screen.VideoPlaybackModel
import com.indirgitsin.app.ui.screen.VideoPlayerScreen
import com.indirgitsin.app.ui.theme.IndirGitsinTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val ACTION_PIP_CONTROL = "com.indirgitsin.app.PIP_CONTROL"
private const val EXTRA_PIP_ACTION = "pip_action"
private const val PIP_ACTION_PLAY = 1
private const val PIP_ACTION_PAUSE = 2
private const val PIP_ACTION_REWIND = 3
private const val PIP_ACTION_FORWARD = 4

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val playback: VideoPlaybackModel by viewModels()
    private val isInPipState = mutableStateOf(false)

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            when (intent.getIntExtra(EXTRA_PIP_ACTION, 0)) {
                PIP_ACTION_PLAY -> playback.player.play()
                PIP_ACTION_PAUSE -> playback.player.pause()
                PIP_ACTION_REWIND -> playback.player.seekTo((playback.player.currentPosition - 10_000).coerceAtLeast(0L))
                PIP_ACTION_FORWARD -> playback.player.seekTo((playback.player.currentPosition + 10_000).coerceAtMost(playback.player.duration.coerceAtLeast(0L)))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipParams()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipParams()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null || uri.scheme !in setOf("content", "file")) { finish(); return }
        enableEdgeToEdge()
        playback.open(uri)

        val filter = IntentFilter(ACTION_PIP_CONTROL)
        ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        playback.player.addListener(playerListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipParams()
        }

        setContent {
            val language by SettingsStore.languageFlow(this).collectAsState(initial = "tr")
            CompositionLocalProvider(LocalAppLanguage provides language) {
                IndirGitsinTheme(darkTheme = true) {
                    VideoPlayerScreen(
                        model = playback,
                        title = intent.getStringExtra("title").orEmpty(),
                        isInPip = isInPipState.value,
                        onEnterPip = { enterPip() },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val isAudio = playback.isAudio(intent.getStringExtra("title").orEmpty())
            if (playback.player.isPlaying && !isAudio) {
                enterPip()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
    }

    override fun onStop() {
        playback.checkpoint()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!isChangingConfigurations && !inPip) {
            val isAudio = playback.isAudio(intent.getStringExtra("title").orEmpty())
            val bgPlay = try { runBlocking { SettingsStore.backgroundPlaybackFlow(this@PlayerActivity).first() } } catch (_: Exception) { true }
            if (!isAudio && !bgPlay) {
                playback.player.pause()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        playback.player.removeListener(playerListener)
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val builder = PictureInPictureParams.Builder()
        val format = playback.player.videoFormat
        if (format != null && format.width > 0 && format.height > 0) {
            val ratio = format.width.toFloat() / format.height.toFloat()
            if (ratio in 0.42f..2.38f) {
                builder.setAspectRatio(Rational(format.width, format.height))
            } else {
                builder.setAspectRatio(Rational(16, 9))
            }
        } else {
            builder.setAspectRatio(Rational(16, 9))
        }

        val isPlaying = playback.player.isPlaying
        val playPauseAction = RemoteAction(
            Icon.createWithResource(this, if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (isPlaying) "Pause" else "Play",
            if (isPlaying) "Pause" else "Play",
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(ACTION_PIP_CONTROL).putExtra(EXTRA_PIP_ACTION, if (isPlaying) PIP_ACTION_PAUSE else PIP_ACTION_PLAY).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        builder.setActions(listOf(playPauseAction))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isAudio = playback.isAudio(intent.getStringExtra("title").orEmpty())
            builder.setAutoEnterEnabled(isPlaying && !isAudio)
        }

        try {
            setPictureInPictureParams(builder.build())
        } catch (_: Exception) {}
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val isAudio = playback.isAudio(intent.getStringExtra("title").orEmpty())
            if (!isAudio) {
                try {
                    updatePipParams()
                    val builder = PictureInPictureParams.Builder()
                    val format = playback.player.videoFormat
                    if (format != null && format.width > 0 && format.height > 0) {
                        val ratio = format.width.toFloat() / format.height.toFloat()
                        if (ratio in 0.42f..2.38f) builder.setAspectRatio(Rational(format.width, format.height))
                        else builder.setAspectRatio(Rational(16, 9))
                    } else builder.setAspectRatio(Rational(16, 9))
                    enterPictureInPictureMode(builder.build())
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        fun intent(context: Context, uri: Uri, title: String) = Intent(context, PlayerActivity::class.java)
            .setData(uri).putExtra("title", title)
    }
}
