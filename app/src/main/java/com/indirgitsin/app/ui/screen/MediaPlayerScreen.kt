package com.indirgitsin.app.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.indirgitsin.app.data.downloader.MediaArtwork
import com.indirgitsin.app.data.lang.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    model: VideoPlaybackModel,
    title: String = "",
    isInPip: Boolean = false,
    onEnterPip: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = context as Activity
    val player = model.player
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var isAudio by remember { mutableStateOf(if (player.currentTracks.isEmpty)
        title.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("mp3", "m4a", "aac", "ogg", "wav")
        else player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var failed by remember { mutableStateOf(player.playerError != null) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var controls by remember { mutableStateOf(true) }
    var speedMenu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var resizeIndex by rememberSaveable { mutableIntStateOf(0) }
    val originalOrientation = rememberSaveable { activity.requestedOrientation }
    val resizeModes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    val resizeLabels = listOf(t("fit_screen"), t("zoom_screen"), t("fill_screen"))

    fun back() { if (fullscreen) { fullscreen = false; controls = true } else onBack() }
    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        controls = true
    }
    fun seek(delta: Long) {
        player.seekTo((player.currentPosition + delta).coerceIn(0L, duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        position = player.currentPosition
        controls = true
    }
    BackHandler { back() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(source: Player, events: Player.Events) {
                isPlaying = source.isPlaying
                duration = source.duration.coerceAtLeast(0)
                position = source.currentPosition
                buffering = source.playbackState == Player.STATE_BUFFERING
                failed = source.playerError != null
                speed = source.playbackParameters.speed
                if (!source.currentTracks.isEmpty) {
                    isAudio = source.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) && !source.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                }
                if (!isPlaying || failed) controls = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Some Android versions reveal system bars while applying the requested rotation.
    // Re-apply immersive state after each configuration update.
    LaunchedEffect(activity, fullscreen, configuration) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < 30) {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY).inv()
            }
        }
        activity.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else originalOrientation
    }
    DisposableEffect(activity) {
        onDispose {
            if (!activity.isChangingConfigurations) {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = originalOrientation
            }
        }
    }
    DisposableEffect(activity, isPlaying, isAudio) {
        if (isPlaying && !isAudio) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(controls, isPlaying, dragging, speedMenu, isAudio) {
        if (controls && isPlaying && !dragging && !speedMenu && !isAudio) { delay(4000); controls = false }
    }
    LaunchedEffect(player, isPlaying, dragging) {
        do {
            if (!dragging) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            if (!isPlaying) break
            delay(300)
        } while (true)
    }

    val artwork by produceState<Pair<MediaArtwork.Entry?, Bitmap?>?>(null, model.uri, isAudio) {
        if (isAudio) {
            val entry = model.uri?.let { MediaArtwork.load(context.applicationContext, it) }
            val bitmap = withContext(Dispatchers.IO) { entry?.jpeg?.let { MediaArtwork.decode(it) } }
            value = entry to bitmap
        } else value = null
    }
    val displayTitle = artwork?.first?.title?.ifBlank { null } ?: title.ifBlank { t("media_player") }
    val progress = if (dragging) dragPosition else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    if (isInPip) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("pip_player")) {
            AndroidView(
                factory = { PlayerView(it).apply { useController = false; useArtwork = false } },
                update = { it.player = player; it.resizeMode = resizeModes[resizeIndex] },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().testTag("video_surface")
            )
            if (isAudio) AudioArtwork(artwork?.second, displayTitle, artwork?.first?.artist.orEmpty())
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player")
        .pointerInput(player, isAudio) {
            detectTapGestures(onTap = { controls = if (isAudio) true else !controls }, onDoubleTap = { offset ->
                when { offset.x < size.width * .35f -> seek(-10_000); offset.x > size.width * .65f -> seek(10_000); else -> togglePlay() }
            })
        }
    ) {
        AndroidView(factory = { PlayerView(it).apply { useController = false; useArtwork = false } },
            update = { it.player = player; it.resizeMode = resizeModes[resizeIndex] },
            onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().testTag("video_surface"))

        if (isAudio) AudioArtwork(artwork?.second, displayTitle, artwork?.first?.artist.orEmpty())

        AnimatedVisibility(controls || isAudio || failed, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isAudio) 0f else .3f)).safeDrawingPadding()) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .35f)).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }, modifier = Modifier.testTag("player_back")) {
                        Icon(Icons.Rounded.ArrowBack, t("back"), tint = Color.White)
                    }
                    Text(displayTitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    if (!isAudio) {
                        IconButton(onClick = { resizeIndex = (resizeIndex + 1) % resizeModes.size }) {
                            Icon(Icons.Rounded.AspectRatio, resizeLabels[resizeIndex], tint = Color.White)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                            IconButton(onClick = onEnterPip, modifier = Modifier.testTag("pip_button")) {
                                Icon(Icons.Rounded.PictureInPictureAlt, t("picture_in_picture"), tint = Color.White)
                            }
                        }
                        IconButton(onClick = { fullscreen = !fullscreen }, modifier = Modifier.testTag("fullscreen_toggle")) {
                            Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                t(if (fullscreen) "exit_fullscreen" else "enter_fullscreen"), tint = Color.White)
                        }
                    }
                    Box {
                        IconButton(onClick = { speedMenu = true }) { Icon(Icons.Rounded.Speed, t("playback_speed"), tint = Color.White) }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                                DropdownMenuItem(text = { Text("${rate}x", fontWeight = if (speed == rate) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { player.setPlaybackSpeed(rate); speedMenu = false })
                            }
                        }
                    }
                }

                if (failed) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp).background(Color.Black.copy(alpha = .85f), RoundedCornerShape(16.dp)).padding(20.dp).testTag("player_error"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("playback_failed"), color = Color.White, textAlign = TextAlign.Center)
                        TextButton(onClick = { player.prepare(); player.play() }) { Text(t("retry")) }
                    }
                } else if (!buffering) {
                    Row(Modifier.align(if (isAudio) Alignment.BottomCenter else Alignment.Center)
                        .padding(bottom = if (isAudio) 104.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { seek(-10_000) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Replay10, t("rewind"), tint = Color.White, modifier = Modifier.size(32.dp)) }
                        FilledIconButton(onClick = { togglePlay() }, shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                            modifier = Modifier.size(64.dp).testTag("play_pause")) {
                            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, t(if (isPlaying) "pause" else "play"), modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { seek(10_000) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Forward10, t("forward"), tint = Color.White, modifier = Modifier.size(32.dp)) }
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .35f)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(if (dragging) (dragPosition * duration).toLong() else position), color = Color.White, fontSize = 12.sp)
                        Text(formatTime(duration), color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                    }
                    Slider(value = progress, enabled = duration > 0 && !failed,
                        onValueChange = { dragging = true; dragPosition = it },
                        onValueChangeFinished = { player.seekTo((dragPosition * duration).toLong()); position = player.currentPosition; dragging = false },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)),
                        modifier = Modifier.fillMaxWidth().height(40.dp).testTag("player_seek"))
                }
            }
        }
        if (buffering && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
    }
}

@Composable
private fun AudioArtwork(bitmap: Bitmap?, title: String, artist: String) {
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF242334), Color(0xFF101017), Color.Black)))
        .safeDrawingPadding().padding(top = 72.dp, bottom = 180.dp).testTag("audio_player"), contentAlignment = Alignment.Center) {
        val coverSize = minOf(280.dp, (maxWidth - 48.dp).coerceAtLeast(48.dp), (maxHeight - 60.dp).coerceAtLeast(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Box(Modifier.size(coverSize).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = .5f), Color(0xFF393954)))), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), t("cover_art"), contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("audio_artwork"))
                else Icon(Icons.Rounded.MusicNote, t("audio_placeholder"), tint = Color.White.copy(alpha = .85f),
                    modifier = Modifier.size(coverSize * .4f).testTag("audio_fallback"))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = .65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
