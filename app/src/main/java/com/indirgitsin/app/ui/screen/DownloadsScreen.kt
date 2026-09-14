package com.indirgitsin.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.indirgitsin.app.data.lang.LocalAppLanguage
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.indirgitsin.app.data.lang.t
import com.indirgitsin.app.data.lang.tr
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.indirgitsin.app.data.downloader.FileDownloader
import com.indirgitsin.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadedFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val dateMillis: Long,
    val mimeType: String,
    val file: File? = null
)

data class ActiveDownload(
    val id: Long,
    val name: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: Int,
    val workId: UUID? = null,
    val stage: String? = null,
    val percent: Int? = null,
    val speed: Long = 0,
    val eta: Long = -1,
    val recording: Boolean = false,
    val resumeId: UUID? = null
)

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language = LocalAppLanguage.current
    var sortOrder by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DownloadedFile?>(null) }
    var retrying by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    val workManager = remember(context) { WorkManager.getInstance(context.applicationContext) }
    val jobs by remember(workManager) { workManager.getWorkInfosByTagFlow(FileDownloader.TAG) }.collectAsState(initial = emptyList())
    val downloadFolder by SettingsStore.downloadSubfolderFlow(context).collectAsState(initial = "IndirGitsin")
    var files by remember { mutableStateOf<List<DownloadedFile>>(emptyList()) }
    var active by remember { mutableStateOf<List<ActiveDownload>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableIntStateOf(0) } // 0: All, 1: Videos, 2: Audio

    val downloadsTitle = t("downloads_title")
    val downloadsSubtitle = t("downloads_subtitle")
    val refreshText = t("refresh")
    val folderText = t("folder")
    val openFolderText = t("open_folder")
    val folderNotFoundText = t("folder_not_found")
    val downloadingCountTemplate = t("downloading_count")
    val cancelledTemplate = t("cancelled")
    val emptyDownloadsTitle = t("empty_downloads_title")
    val emptyDownloadsDesc = t("empty_downloads_desc")

    fun refresh() {
        scope.launch {
            loading = true
            val snapshot = withContext(Dispatchers.IO) {
                scanActiveDownloads(context) to (completedDownloads(context, jobs) + scanDownloads(context, downloadFolder)).distinctBy { it.uri }
            }
            active = snapshot.first
            files = snapshot.second.filterNot { file -> snapshot.first.any { it.name == file.name } }
            loading = false
        }
    }

    LaunchedEffect(downloadFolder, jobs.map { it.id to it.state }) { refresh() }

    val queueOrder by com.indirgitsin.app.data.downloader.DownloadQueueCoordinator.queueOrder.collectAsState()

    val workerActive = jobs.filter { !it.state.isFinished }.map { job ->
        val rank = queueOrder[job.id]
        val stageRaw = job.progress.getString("stage")
        val isQueued = stageRaw == "Sırada" || job.state == WorkInfo.State.ENQUEUED
        ActiveDownload(
            job.id.mostSignificantBits,
            job.progress.getString("name") ?: job.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:") ?: "Video",
            job.progress.getLong("bytes", 0),
            job.progress.getLong("total", -1),
            if (job.state == WorkInfo.State.RUNNING && !isQueued) android.app.DownloadManager.STATUS_RUNNING else android.app.DownloadManager.STATUS_PENDING,
            job.id,
            if (job.state == WorkInfo.State.ENQUEUED) {
                if ("network:unmetered" in job.tags) tr(language, "waiting_unmetered") else tr(language, "waiting_network")
            } else {
                val stage = stageRaw ?: tr(language, "status_pending")
                if (rank != null && stage == "Sırada") "${tr(language, "queue_rank", rank)} • Sırada" else stage
            },
            job.progress.getInt("percent", 0),
            if (job.state == WorkInfo.State.RUNNING && !isQueued) job.progress.getLong("speed", 0) else 0,
            if (job.state == WorkInfo.State.RUNNING && !isQueued) job.progress.getLong("eta", -1) else -1,
            job.state == WorkInfo.State.RUNNING && job.progress.getBoolean("recording", false),
            job.tags.firstOrNull { it.startsWith("resume:") }?.removePrefix("resume:")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        )
    }.sortedWith(compareBy<ActiveDownload> { it.status != android.app.DownloadManager.STATUS_RUNNING }
        .thenBy { queueOrder[it.workId] ?: Int.MAX_VALUE })
    val allActive = active + workerActive

    // Aktif indirmeler varsa periyodik yenile
    LaunchedEffect(active.isNotEmpty()) {
        while (active.isNotEmpty()) {
            kotlinx.coroutines.delay(1000)
            active = withContext(Dispatchers.IO) { scanActiveDownloads(context) }
            if (active.isEmpty()) {
                val allFiles = withContext(Dispatchers.IO) { scanDownloads(context, downloadFolder) }
                val activeNames = active.map { it.name }.toSet()
                files = allFiles.filterNot { f -> activeNames.contains(f.name) }
            }
        }
    }

    val totalBytes = remember(files) { files.sumOf { it.sizeBytes } }

    val filteredFiles = remember(files, searchQuery, selectedFilter, sortOrder) {
        files.filter { item ->
            val matchesQuery = searchQuery.isBlank() || item.name.contains(searchQuery.trim(), ignoreCase = true)
            val ext = item.name.substringAfterLast('.', "").lowercase()
            val isAudio = item.mimeType.startsWith("audio/")
            val isVideo = item.mimeType.startsWith("video/")
            val matchesFilter = when (selectedFilter) {
                1 -> isVideo
                2 -> isAudio
                else -> true
            }
            matchesQuery && matchesFilter
        }.let { matches ->
            when (sortOrder) {
                1 -> matches.sortedBy { it.name.lowercase(Locale.ROOT) }
                2 -> matches.sortedByDescending { it.sizeBytes }
                else -> matches.sortedByDescending { it.dateMillis }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text(t("delete_confirm_title")) },
            text = { Text(t("delete_confirm_body", item.name)) },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null
                scope.launch { deleteFile(context, item); refresh() }
            }) { Text(t("delete")) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t("cancel")) } })
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Başlık ve Üst Butonlar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(downloadsTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${files.size} $downloadsSubtitle • ${t("total_storage")}: ${formatSize(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, contentDescription = t("sort")) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("sort_newest", "sort_name", "sort_size").forEachIndexed { index, key ->
                        DropdownMenuItem(text = { Text(t(key)) }, onClick = { sortOrder = index; sortMenu = false },
                            trailingIcon = { if (sortOrder == index) Icon(Icons.Rounded.Check, null) })
                    }
                }
            }
            IconButton(onClick = { refresh() }) { Icon(Icons.Rounded.Refresh, contentDescription = refreshText) }
            if (files.isNotEmpty()) {
                IconButton(onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString()), "resource/folder")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, openFolderText))
                    } catch (_: Exception) {
                        Toast.makeText(context, folderNotFoundText, Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Rounded.FolderOpen, contentDescription = folderText) }
            }
        }

        // Arama ve Filtre Çipleri
        if (files.isNotEmpty() || allActive.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(t("search_downloads")) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Temizle")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 },
                    label = { Text(t("filter_all")) },
                    shape = RoundedCornerShape(8.dp)
                )
                FilterChip(
                    selected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 },
                    label = { Text(t("filter_videos")) },
                    leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null, modifier = Modifier.size(16.dp)) },
                    shape = RoundedCornerShape(8.dp)
                )
                FilterChip(
                    selected = selectedFilter == 2,
                    onClick = { selectedFilter = 2 },
                    label = { Text(t("filter_audios")) },
                    leadingIcon = { Icon(Icons.Rounded.Audiotrack, null, modifier = Modifier.size(16.dp)) },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // Aktif indirmeler
        if (allActive.isNotEmpty()) {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val downloadingCountText = try { String.format(downloadingCountTemplate, allActive.size) } catch (_: Exception) { downloadingCountTemplate }
                Text(downloadingCountText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                allActive.forEach { item ->
                    val isWaiting = item.workId != null && (item.stage?.contains("Sırada") == true || item.status == android.app.DownloadManager.STATUS_PENDING)
                    ActiveDownloadCard(item, onStop = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    item.resumeId?.let { com.indirgitsin.app.data.downloader.LiveRecordingControl.requestStop(context, it) }
                                }
                                Toast.makeText(context, tr(language, "live_stopping"), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show() }
                        }
                    }, onPrioritize = if (isWaiting && item.workId != null) {
                        {
                            scope.launch {
                                FileDownloader.prioritize(item.workId)
                                Toast.makeText(context, tr(language, "prioritized_toast"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else null, onCancel = {
                        try {
                            if (item.workId != null) FileDownloader.cancel(context, item.workId)
                            else {
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                dm.remove(item.id)
                            }
                            val msg = try { String.format(cancelledTemplate, item.name) } catch (_: Exception) { cancelledTemplate }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                        refresh()
                    })
                }
            }
        }

        jobs.filter { it.state == WorkInfo.State.FAILED }.takeLast(3).forEach { job ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${job.outputData.getString("name") ?: "Video"}: ${job.outputData.getString("error") ?: "İndirme başarısız"}",
                    modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!job.outputData.getString("videoId").isNullOrBlank()) {
                    TextButton(enabled = job.id !in retrying, onClick = {
                        retrying = retrying + job.id
                        scope.launch {
                            try {
                                val added = FileDownloader.retry(context, job)
                                Toast.makeText(context, tr(language, if (added) "retry_queued" else "already_queued"), Toast.LENGTH_SHORT).show()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                            finally { retrying = retrying - job.id }
                        }
                    }) { Text(t("retry")) }
                }
            }
        }
        jobs.filter { it.state == WorkInfo.State.SUCCEEDED && !it.outputData.getString("note").isNullOrBlank() }.takeLast(2).forEach { job ->
            Text(job.outputData.getString("note").orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (files.isEmpty() && allActive.isEmpty()) {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(64.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.DownloadDone, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(emptyDownloadsTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(emptyDownloadsDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { refresh() }, shape = RoundedCornerShape(20.dp)) { Text(refreshText) }
                }
            }
        } else if (filteredFiles.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(t("no_downloads"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(filteredFiles, key = { it.uri.toString() }) { item ->
                    DownloadCard(
                        item = item,
                        onPlay = {
                            context.startActivity(com.indirgitsin.app.PlayerActivity.intent(context, item.uri, item.name))
                        },
                        onOpenExternal = { playFile(context, item) },
                        onShare = { shareFile(context, item) },
                        onDelete = { deleteTarget = item }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadedFile,
    onPlay: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val sizeText = formatSize(item.sizeBytes)
    val dateText = formatDate(item.dateMillis)
    val ext = item.name.substringAfterLast('.', "").uppercase()
    var showMenu by remember { mutableStateOf(false) }
    val isAudioExt = item.mimeType.startsWith("audio/")
    val isVideo = !isAudioExt && (item.mimeType.startsWith("video") || item.name.endsWith(".mp4", true) || item.name.endsWith(".webm", true) || item.name.endsWith(".mkv", true))
    val typeLabel = when {
        isAudioExt -> t("type_audio")
        isVideo -> t("type_video")
        else -> t("type_file")
    }
    val typeColor = if (isVideo) MaterialTheme.colorScheme.primary else Color(0xFF0F9D58)
    val qualityFromName = Regex("_(\\d{3,4}p|\\d+kbps|\\d+k)_?").find(item.name)?.groupValues?.getOrNull(1)?.uppercase() ?: ""
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = typeColor.copy(alpha = 0.12f), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(if (isVideo) Icons.Rounded.VideoFile else Icons.Rounded.AudioFile, null, tint = typeColor)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Surface(shape = RoundedCornerShape(6.dp), color = typeColor) {
                        Text(typeLabel, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(ext.ifBlank { "?" }, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    if (qualityFromName.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(qualityFromName, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Text("$sizeText • $dateText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, contentDescription = t("play_internal"), tint = MaterialTheme.colorScheme.primary) }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Daha fazla") }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(t("play_internal")) },
                        leadingIcon = { Icon(Icons.Rounded.PlayCircle, null) },
                        onClick = {
                            showMenu = false
                            onPlay()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("open_with")) },
                        leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) },
                        onClick = {
                            showMenu = false
                            onOpenExternal()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("share")) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = {
                            showMenu = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("delete"), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun completedDownloads(context: Context, jobs: List<WorkInfo>): List<DownloadedFile> =
    jobs.filter { it.state == WorkInfo.State.SUCCEEDED }.mapNotNull { job ->
        val data = job.outputData
        val uri = data.getString("uri")?.let(Uri::parse) ?: return@mapNotNull null
        val exists = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false } catch (_: Exception) { false }
        if (!exists) return@mapNotNull null
        DownloadedFile(data.getString("name") ?: "Video", uri, data.getLong("size", 0),
            data.getLong("completedAt", 0), data.getString("mime") ?: "video/mp4")
    }

private fun scanDownloads(context: Context, folder: String): List<DownloadedFile> {
    val result = mutableListOf<DownloadedFile>()
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads._ID
            )
            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection, "${MediaStore.Downloads.IS_PENDING} = 0 AND ${MediaStore.Downloads.RELATIVE_PATH} IN (?, ?)",
                    arrayOf("Download/$folder/", "Download/IndirGitsin/"), "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: continue
                        if (!name.endsWith(".mp4", true) && !name.endsWith(".m4a", true) && !name.endsWith(".mp3", true) && !name.endsWith(".webm", true) && !name.endsWith(".mkv", true)) continue
                        val size = cursor.getLong(sizeIdx)
                        val dateSec = cursor.getLong(dateIdx)
                        val mime = cursor.getString(mimeIdx) ?: "video/mp4"
                        val id = cursor.getLong(idIdx)
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        if (result.none { it.name == name }) result.add(DownloadedFile(name, uri, size, dateSec * 1000, mime))
                    }
                }
            } catch (_: Exception) {}
        }

        // File-based scanning fallback (IndirGitsin alt klasörü ve indirme kökü)
        try {
            @Suppress("DEPRECATION")
            val downloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dirsToScan = if (Build.VERSION.SDK_INT < 29) listOf(File(downloadRoot, folder), File(downloadRoot, "IndirGitsin")) else emptyList()
            for (dir in dirsToScan.distinctBy { it.absolutePath }) {
                if (!dir.exists() || !dir.isDirectory) continue
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".mp4", true) || f.name.endsWith(".m4a", true) || f.name.endsWith(".mp3", true) || f.name.endsWith(".webm", true) || f.name.endsWith(".mkv", true))) {
                        if (result.any { it.name == f.name }) return@forEach
                        val mime = when {
                            f.name.endsWith(".mp3", true) -> "audio/mpeg"
                            f.name.endsWith(".m4a", true) -> "audio/mp4"
                            f.name.endsWith(".webm", true) -> "video/webm"
                            else -> "video/mp4"
                        }
                        result.add(DownloadedFile(f.name, Uri.fromFile(f), f.length(), f.lastModified(), mime, f))
                    }
                }
            }
        } catch (_: Exception) {}

        result.sortByDescending { it.dateMillis }
    } catch (_: Exception) {}
    return result
}

private fun scanActiveDownloads(context: Context): List<ActiveDownload> {
    val result = mutableListOf<ActiveDownload>()
    try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val q = android.app.DownloadManager.Query().setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING or android.app.DownloadManager.STATUS_PAUSED or android.app.DownloadManager.STATUS_PENDING)
        dm.query(q)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID)
            val titleIdx = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TITLE)
            val bytesIdx = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusIdx = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val lang = Locale.getDefault().language
                val fallback = tr(lang, "downloading")
                val name = c.getString(titleIdx) ?: fallback
                val dl = c.getLong(bytesIdx)
                val total = c.getLong(totalIdx)
                val status = c.getInt(statusIdx)
                result.add(ActiveDownload(id, name, dl, total, status))
            }
        }
    } catch (_: Exception) {}
    return result
}

@Composable
private fun ActiveDownloadCard(
    item: ActiveDownload,
    onStop: () -> Unit,
    onPrioritize: (() -> Unit)? = null,
    onCancel: () -> Unit
) {
    val progress = item.percent?.div(100f) ?: if (item.totalBytes > 0) item.bytesDownloaded.toFloat() / item.totalBytes else 0f
    val statusText = item.stage ?: when (item.status) {
        android.app.DownloadManager.STATUS_RUNNING -> t("status_running")
        android.app.DownloadManager.STATUS_PAUSED -> t("status_paused")
        android.app.DownloadManager.STATUS_PENDING -> t("status_pending")
        else -> t("status_processing")
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text("$statusText • ${formatSize(item.bytesDownloaded)} / ${if (item.totalBytes>0) formatSize(item.totalBytes) else "?"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
                if (!item.recording) Text("${(progress*100).toInt()}%", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                if (onPrioritize != null) {
                    IconButton(onClick = onPrioritize) {
                        Icon(Icons.Rounded.VerticalAlignTop, contentDescription = t("prioritize"), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = t("cancel"), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            if (item.recording && item.resumeId != null) FilledTonalButton(onClick = onStop) {
                Icon(Icons.Rounded.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text(t("stop_and_save"))
            }
            if (item.speed > 0) {
                val remaining = if (item.eta >= 0) t("remaining_estimate", formatRemaining(item.eta)) else ""
                Text("${formatSize(item.speed)}/s  $remaining", style = MaterialTheme.typography.labelSmall)
            }
            if (progress > 0) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun playFile(context: Context, item: DownloadedFile) {
    val lang = Locale.getDefault().language
    try {
        val uri = if (item.file != null) {
            try { FileProvider.getUriForFile(context, "${context.packageName}.provider", item.file) } catch (_: Exception) { item.uri }
        } else item.uri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, tr(lang, "play_title", item.name)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, tr(lang, "no_player"), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, tr(lang, "open_failed", e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, item: DownloadedFile) {
    val lang = Locale.getDefault().language
    try {
        val uri = if (item.file != null) {
            try { FileProvider.getUriForFile(context, "${context.packageName}.provider", item.file) } catch (_: Exception) { item.uri }
        } else item.uri
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, tr(lang, "share_title", item.name)))
    } catch (e: Exception) {
        Toast.makeText(context, tr(lang, "share_failed", e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

private suspend fun deleteFile(context: Context, item: DownloadedFile) {
    val lang = Locale.getDefault().language
    try {
        withContext(Dispatchers.IO) {
            if (item.file == null) {
                check(context.contentResolver.delete(item.uri, null, null) > 0) { "Dosya silinemedi." }
            } else {
                check(item.file.delete()) { "Dosya silinemedi." }
            }
            com.indirgitsin.app.data.downloader.MediaArtwork.remove(context, item.uri)
        }
        Toast.makeText(context, tr(lang, "deleted", item.name), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, tr(lang, "delete_failed", e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
    bytes == 0L -> "—"
    else -> "$bytes B"
}

private fun formatDate(millis: Long): String = try {
    SimpleDateFormat("dd MMM yyyy HH:mm", Locale("tr", "TR")).format(Date(millis))
} catch (_: Exception) { "" }

private fun formatRemaining(seconds: Long): String = when {
    seconds >= 3600 -> "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else -> "%d:%02d".format(seconds / 60, seconds % 60)
}
