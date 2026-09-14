package com.indirgitsin.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.indirgitsin.app.data.history.HistoryDao
import com.indirgitsin.app.data.lang.t
import com.indirgitsin.app.data.model.StreamOption
import com.indirgitsin.app.data.model.UiState
import com.indirgitsin.app.data.model.VideoInfo
import com.indirgitsin.app.ui.theme.YtRed
import com.indirgitsin.app.util.YoutubeLinkHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.indirgitsin.app.data.SettingsStore
import com.indirgitsin.app.data.model.StreamSelector

private fun safeFormat(format: String, vararg args: Any): String = try {
    String.format(format, *args)
} catch (_: Exception) {
    format
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    inputUrl: String,
    onInputChange: (String) -> Unit,
    uiState: UiState,
    onFetch: (String) -> Unit,
    onDownload: (VideoInfo, StreamOption) -> Unit,
    onPaste: () -> Unit,
    historyDao: HistoryDao? = null,
    onHistoryClick: (String) -> Unit = {},
    onClear: () -> Unit = {}
) {
    var showSheet by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val context = LocalContext.current

    val doPaste = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
        val url = YoutubeLinkHelper.findMediaUrlInText(text) ?: text
        if (!url.isNullOrBlank()) {
            onInputChange(url)
            if (YoutubeLinkHelper.isSupportedMediaUrl(url)) onFetch(url)
        }
        onPaste()
    }

    LaunchedEffect(uiState) {
        if (uiState is UiState.Success) {
            selectedVideo = uiState.video
            selectedTab = 0
            // sheet otomatik acilmasin - sadece Indir'e basinca acilsin
        } else if (uiState is UiState.Loading || uiState is UiState.Idle) {
            // yuklenirken onceki sheet'i gizle
            showSheet = false
        }
    }

    Scaffold(
        topBar = { HomeTopBar() },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("home"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("home_headline"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(t("home_subtitle"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "input") {
                    HomeLinkCard(inputUrl, onInputChange, onFetch, doPaste, uiState is UiState.Loading)
                }
                if (uiState !is UiState.Idle) {
                    item(key = "result") {
                        AnimatedContent(targetState = uiState,
                            transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "state"
                        ) { state ->
                            when (state) {
                                is UiState.Loading -> YtShimmerCard()
                                is UiState.Error -> YtErrorCard(state.message)
                                is UiState.Success -> Box {
                                    YtVideoCard(video = state.video, onClick = { showSheet = true })
                                    SmallCloseButton(onClick = {
                                        onClear(); showSheet = false; selectedVideo = null
                                    }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                                }
                                is UiState.PlaylistSuccess -> YtPlaylistCard(playlist = state.playlist)
                                else -> Unit
                            }
                        }
                    }
                }
                if (uiState is UiState.Idle) {
                    item(key = "benefits") { HomeBenefits() }
                    item(key = "help") { YtHowItWorks() }
                }
                item(key = "recent") { YtRecentSection(historyDao, onHistoryClick) }
                item(key = "rights") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(t("tos_notice"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showSheet && selectedVideo != null) {
        YtDownloadSheet(
            video = selectedVideo!!,
            selectedTab = selectedTab,
            onTabChange = { selectedTab = it },
            onDismiss = { showSheet = false },
            onDownload = { opt -> onDownload(selectedVideo!!, opt); showSheet = false },
            onPreview = { /* handled inside sheet now via direct intent */ },
            filterChip = 0
        )
    }
}

@Composable
private fun HomeTopBar() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
            }
        }
        Column {
            Text(t("app_name"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(t("home_tagline"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeLinkCard(
    inputUrl: String, onInputChange: (String) -> Unit, onFetch: (String) -> Unit,
    onPaste: () -> Unit, loading: Boolean
) {
    val canFetch = inputUrl.isNotBlank() && !loading
    val submit = { if (canFetch) onFetch(inputUrl) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Link, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(t("link_label"), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
            OutlinedTextField(
                value = inputUrl, onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth().testTag("link_input"),
                placeholder = { Text("https://youtu.be/…", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = if (inputUrl.isNotBlank()) { {
                    IconButton(onClick = { onInputChange("") }) { Icon(Icons.Rounded.Close, t("clear")) }
                } } else null
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPaste, enabled = !loading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("paste"))
                }
                Button(onClick = submit, enabled = canFetch,
                    modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag("resolve_button"),
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("resolve"), fontWeight = FontWeight.SemiBold)
                }
            }
            Text("YouTube · Shorts · SoundCloud · Bandcamp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeBenefits() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(Icons.Rounded.GraphicEq to ("home_av" to "home_av_detail"),
            Icons.Rounded.HighQuality to ("home_original" to "home_original_detail")).forEach { (icon, keys) ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(t(keys.first), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(t(keys.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun YtShimmerCard() {
    val transition = rememberInfiniteTransition(label = "ytShimmer")
    val alpha by transition.animateFloat(initialValue = 0.3f, targetValue = 0.8f, animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.3f)))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.85f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.4f)))
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.2f)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.2f)))
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(YtRed.copy(alpha = alpha * 0.2f)))
                }
            }
        }
    }
}

@Composable
private fun YtErrorCard(message: String) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun YtEmptyState() {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.OndemandVideo, null, tint = YtRed, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(t("empty_state"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun YtRecentSection(historyDao: HistoryDao?, onHistoryClick: (String) -> Unit) {
    var recent by remember { mutableStateOf<List<com.indirgitsin.app.data.history.HistoryEntity>>(emptyList()) }
    LaunchedEffect(historyDao) { historyDao?.observeHistory()?.collect { recent = it.take(4) } }
    if (recent.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(t("recent_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))

        }
        if (recent.isEmpty()) {
            Text(t("recent_empty"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { item ->
                    YtHistoryRow(item = item, onClick = { onHistoryClick(item.url) })
                }
            }
        }
    }
}

@Composable
private fun YtHistoryRow(item: com.indirgitsin.app.data.history.HistoryEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg" },
                contentDescription = null,
                modifier = Modifier.size(width = 100.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(item.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun YtHowItWorks() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("how_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            YtStep("1", t("step_link"), Icons.Rounded.Link)
            YtStep("2", t("step_quality"), Icons.Rounded.Tune)
            YtStep("3", t("step_download"), Icons.Rounded.Download)
        }
    }
}

@Composable
private fun RowScope.YtStep(num: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("0$num", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun YtVideoCard(video: VideoInfo, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column {
            Box {
                AsyncImage(
                    model = video.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg" },
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    contentScale = ContentScale.Crop
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.8f)
                ) {
                    Text(
                        YoutubeLinkHelper.formatDuration(video.durationSeconds),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                // Platform & Quality badges
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (video.serviceName.lowercase(Locale.ROOT)) {
                            "soundcloud" -> Color(0xFFFF5500)
                            "bandcamp" -> Color(0xFF1DA0C3)
                            else -> YtRed
                        }
                    ) {
                        Text(" ${video.serviceName} ", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.75f)
                    ) {
                        Text(" " + video.streams.size + " " + t("quality") + " ", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text(video.author.take(1).uppercase(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.2)
                    Text("${video.author} • ${formatViews(video.viewCount)} • ${YoutubeLinkHelper.formatDuration(video.durationSeconds)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // YouTube chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(video.streams.firstOrNull { it.isVideo }?.quality ?: t("audio"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AudioFile, null, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(video.streams.firstOrNull { it.isAudioOnly }?.extension?.uppercase() ?: t("audio"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) { Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            }
            // YouTube style action row
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp)) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("download"), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("preview"))
                }
            }
        }
    }
}

private fun formatViews(v: Long): String = when {
    v >= 1_000_000 -> String.format("%.1f Mn", v / 1_000_000.0).replace(".0", "")
    v >= 1_000 -> String.format("%.1f B", v / 1_000.0).replace(".0", "")
    v == 0L -> "—"
    else -> v.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YtDownloadSheet(
    video: VideoInfo,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDownload: (StreamOption) -> Unit,
    onPreview: () -> Unit,
    filterChip: Int = 0
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settingsContext = LocalContext.current
    val autoHigh by SettingsStore.autoHighFlow(settingsContext).collectAsState(initial = true)
    val audioFormat by SettingsStore.audioFormatFlow(settingsContext).collectAsState(initial = "M4A")
    var recordMinutes by rememberSaveable(video.id) { mutableIntStateOf(15) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = video.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg" },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(video.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val context = LocalContext.current
                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(video.url))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Rounded.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            // YouTube Premium style segmented
            if (!video.isLive) SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(selected = selectedTab == 0, onClick = { onTabChange(0) }, shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp), icon = { Icon(Icons.Rounded.Videocam, null, modifier = Modifier.size(16.dp)) }) { Text(t("video")) }
                SegmentedButton(selected = selectedTab == 1, onClick = { onTabChange(1) }, shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp), icon = { Icon(Icons.Rounded.MusicNote, null, modifier = Modifier.size(16.dp)) }) { Text(t("audio")) }
            }
            if (video.isLive) {
                Text(t("live_record_hint"), style = MaterialTheme.typography.bodySmall)
                Text(t("live_duration"), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { minutes ->
                        FilterChip(selected = recordMinutes == minutes, onClick = { recordMinutes = minutes },
                            label = { Text(t("minutes_value", minutes)) })
                    }
                }
            } else if (selectedTab == 1) Text(t("mp3_conversion_hint"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
// Chip filtreleri: 0 Tümü (tab'a göre), 1 Video, 2 Music, 3 Shorts, 4 4K
            // Video tab: sesli mux'lar (parantezsiz) + sentezlenmiş mux'lar; video-only'ler gizli (artık otomatik birleştiriliyor)
            val recommended = StreamSelector.preferred(video.streams, autoHigh, audioFormat)
            val muxedStreams = video.streams.filter { it.isVideo && it.isDownloadable }
                .sortedBy { if (it == recommended) 0 else 1 }
            val audioOnlyStreams = video.streams.filter { it.isAudioOnly && it.isDownloadable }
                .sortedWith(compareByDescending<StreamOption> { it.extension.equals(audioFormat, true) }.thenByDescending { it.bitrate })
            val baseByTab = if (selectedTab == 0 || video.isLive) muxedStreams else audioOnlyStreams
            val (filtered, chipNote) = when (if (video.isLive) 0 else filterChip) {
                1 -> baseByTab.filter { it.isVideo } to null
                2 -> audioOnlyStreams to null
                3 -> if (video.durationSeconds in 1..65) baseByTab to null else emptyList<StreamOption>() to t("shorts_not", YoutubeLinkHelper.formatDuration(video.durationSeconds))
                4 -> {
                    val fourK = baseByTab.filter { it.quality.contains("2160") || it.label.contains("2160") || it.quality.contains("1440") || it.label.contains("1440") || it.label.contains("4K", true) || it.quality.contains("4K", true) }
                    fourK to if (fourK.isEmpty()) t("no_4k", baseByTab.firstOrNull()?.quality ?: "—") else null
                }
                else -> baseByTab to null
            }
            if (chipNote != null) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(chipNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            if (filtered.isEmpty() && chipNote == null) {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(t("no_filter"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (filtered.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    filtered.forEach { opt ->
                        YtOptionRow(label = if (opt.isLive) t("live_record") else opt.label,
                            sublabel = "${opt.extension.uppercase()} • ${if (opt.isVideo) t("sublabel_video") else t("sublabel_audio")}",
                            isVideo = opt.isVideo, actionLabel = if (opt.isLive) t("record") else null,
                            onClick = { onDownload(if (opt.isLive) opt.copy(recordMinutes = recordMinutes) else opt) })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t("downloads_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun YtOptionRow(label: String, sublabel: String, isVideo: Boolean, actionLabel: String? = null, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = YtRed.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(if (isVideo) Icons.Rounded.VideoFile else Icons.Rounded.Headphones, null, tint = YtRed, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(sublabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = YtRed, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(actionLabel ?: t("download"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun YtPlaylistCard(playlist: com.indirgitsin.app.data.model.PlaylistInfo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember(playlist.id) { mutableStateOf(setOf<String>()) }
    var downloading by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }
    var currentProcessing by remember { mutableStateOf("") }
    val allSelected = selected.size == playlist.videos.size && playlist.videos.isNotEmpty()
    val toastSelectFirst = t("toast_select_first")
    val queueAddingTemplate = t("queue_adding")
    val queueAddedText = t("queue_added")
    val downloadingProgressTemplate = t("downloading_progress")
    val gotoDownloadsText = t("goto_downloads")
    val queueHintText = t("queue_hint")
    val queueAddedCountTemplate = t("queue_added_count")
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = playlist.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${playlist.videos.firstOrNull()?.id}/hqdefault.jpg" }, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("${playlist.author} • ${playlist.videos.size} video", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = allSelected, onClick = { selected = if (allSelected) emptySet() else playlist.videos.map { it.id }.toSet() }, label = { Text(if (allSelected) t("deselect_all") else t("select_all")) })
                Spacer(Modifier.weight(1f))
                Text(selected.size.toString() + " " + t("selected"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (playlist.videos.isEmpty()) {
                Text(t("empty_playlist"), style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    playlist.videos.forEach { v ->
                        val isSel = selected.contains(v.id)
                        val isProcessing = downloading && currentProcessing == v.id
                        val isDone = addedCount > 0 && !downloading && selected.contains(v.id)
                        Surface(onClick = { if (!downloading) selected = if (isSel) selected - v.id else selected + v.id }, shape = RoundedCornerShape(12.dp), color = when {
                            isProcessing -> MaterialTheme.colorScheme.primaryContainer
                            isDone -> MaterialTheme.colorScheme.tertiaryContainer
                            isSel -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else if (isDone) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
                                } else {
                                    Checkbox(checked = isSel, onCheckedChange = { if (!downloading) selected = if (it) selected + v.id else selected - v.id })
                                }
                                AsyncImage(model = v.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${v.id}/hqdefault.jpg" }, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    if (isProcessing) {
                                        Text(t("queue_adding"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else if (isDone) {
                                        Text(t("queue_added"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    } else {
                                        Text(v.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    if (selected.isEmpty()) {
                        android.widget.Toast.makeText(context, toastSelectFirst, android.widget.Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    downloading = true
                    addedCount = 0
                    scope.launch {
                        try {
                            val ok = com.indirgitsin.app.data.downloader.FileDownloader.enqueuePlaylist(
                                context, playlist.videos.filter { it.id in selected },
                                SettingsStore.autoHighFlow(context).first(), SettingsStore.audioFormatFlow(context).first())
                            addedCount = ok
                            val finalToast = safeFormat(queueAddedCountTemplate, ok)
                            android.widget.Toast.makeText(context, finalToast, android.widget.Toast.LENGTH_LONG).show()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, e.message ?: "Kuyruğa eklenemedi", android.widget.Toast.LENGTH_LONG).show()
                        } finally {
                            downloading = false
                            currentProcessing = ""
                        }
                    }
                },
                enabled = selected.isNotEmpty() && !downloading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (downloading) {
                    Row(Modifier.width(16.dp).align(Alignment.CenterVertically)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                } else {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
Text(
                    when {
                        downloading -> safeFormat(t("downloading_progress"), addedCount, selected.size)
                        addedCount > 0 -> safeFormat(t("added_count"), addedCount)
                        else -> safeFormat(t("download_selected"), selected.size)
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            if (addedCount > 0 && !downloading) {
                TextButton(
                    onClick = {
                        // Navigate to Downloads tab - parent should handle this
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("goto_downloads"), fontWeight = FontWeight.Bold)
                    Icon(Icons.Rounded.ArrowForward, null, modifier = Modifier.size(16.dp))
                }
            } else {
                Text(t("queue_hint"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SmallCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.6f),
        modifier = modifier.size(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Rounded.Close, contentDescription = t("remove"), tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Preview(showBackground = true, name = "YouTube Premium Preview")
@Composable
private fun YtPreview() {
    com.indirgitsin.app.ui.theme.IndirGitsinTheme {
        HomeScreen(
            inputUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            onInputChange = {},
            uiState = com.indirgitsin.app.data.model.UiState.Success(
                com.indirgitsin.app.data.model.VideoInfo(
                    id = "dQw4w9WgXcQ", title = "YouTube Premium - İndir Gitsin Demo: 4K Video Nasıl İndirilir? Uzun Başlık Testi", author = "Demo Kanal",
                    thumbnailUrl = "", durationSeconds = 212, viewCount = 1234567, url = "",
                    streams = listOf(
                        com.indirgitsin.app.data.model.StreamOption("1080p • MP4", "mp4", "1080p", "https://example.com", true, false),
                        com.indirgitsin.app.data.model.StreamOption("720p • MP4", "mp4", "720p", "https://example.com", true, false),
                        com.indirgitsin.app.data.model.StreamOption("Ses • M4A 128kbps", "m4a", "128kbps", "https://example.com", false, true, bitrate = 128)
                    )
                )
            ),
            onFetch = { _ -> },
            onDownload = { _, _ -> },
            onPaste = {}
        )
    }
}
