package com.indirgitsin.app.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indirgitsin.app.data.SettingsStore
import com.indirgitsin.app.data.history.AppDatabase
import com.indirgitsin.app.data.lang.t
import com.indirgitsin.app.data.lang.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.indirgitsin.app.util.UpdateChecker
import java.io.File

private fun calcCacheSizeBytes(context: Context): Long {
    return try {
        context.cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    } catch (_: Exception) { 0L }
}

private fun formatByteSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
    bytes == 0L -> "0 KB"
    else -> "$bytes B"
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unmetered by SettingsStore.unmeteredFlow(context).collectAsState(initial = false)
    val backgroundPlayback by SettingsStore.backgroundPlaybackFlow(context).collectAsState(initial = true)
    var updateUnavailable by remember { mutableStateOf(false) }
    var confirmHistoryClear by remember { mutableStateOf(false) }
    var showNotices by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmPartialClear by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf("") }
    val autoHigh by SettingsStore.autoHighFlow(context).collectAsState(initial = true)
    val audioFormat by SettingsStore.audioFormatFlow(context).collectAsState(initial = "M4A")
    val downloadFolder by SettingsStore.downloadSubfolderFlow(context).collectAsState(initial = "IndirGitsin")
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderInput by remember { mutableStateOf("") }
    val version = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0" } catch (_: Exception) { "1.0.0" } }
    var checking by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<com.indirgitsin.app.util.UpdateChecker.UpdateInfo?>(null) }
    val theme by SettingsStore.themeFlow(context).collectAsState(initial = "dark")
    val appColor by SettingsStore.appColorFlow(context).collectAsState(initial = "red")
    val language by SettingsStore.languageFlow(context).collectAsState(initial = "tr")
    var cacheSizeBytes by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) { cacheSizeBytes = withContext(Dispatchers.IO) { calcCacheSizeBytes(context) } }
    LaunchedEffect(showNotices, showPrivacy, language) {
        if (showNotices || showPrivacy) notices = withContext(Dispatchers.IO) {
            val asset = if (showPrivacy) "PRIVACY_${if (language == "en") "en" else "tr"}.txt" else "THIRD_PARTY_NOTICES.txt"
            val text = context.assets.open(asset).bufferedReader().use { it.readText() }
            if (showPrivacy) text else text + "\n\nLAME LGPL license:\n" +
                context.assets.open("LICENSE_LAME_LGPL2.txt").bufferedReader().use { it.readText() }
        }
    }
    if (showNotices || showPrivacy) AlertDialog(onDismissRequest = { showNotices = false; showPrivacy = false },
        title = { Text(t(if (showPrivacy) "privacy_policy" else "license")) },
        text = { Text(notices, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showNotices = false; showPrivacy = false }) { Text(t("close")) } })
    if (confirmPartialClear) AlertDialog(onDismissRequest = { confirmPartialClear = false },
        title = { Text(t("partial_clear")) }, text = { Text(t("partial_clear_sub")) },
        confirmButton = { TextButton(onClick = {
            confirmPartialClear = false
            scope.launch {
                try {
                    com.indirgitsin.app.data.downloader.PartialDownloads.clearInactive(context)
                    Toast.makeText(context, tr(language, "partial_cleared"), Toast.LENGTH_SHORT).show()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { Toast.makeText(context, tr(language, "operation_failed"), Toast.LENGTH_SHORT).show() }
            }
        }) { Text(t("clear")) } },
        dismissButton = { TextButton(onClick = { confirmPartialClear = false }) { Text(t("cancel")) } })
    if (updateUnavailable) AlertDialog(onDismissRequest = { updateUnavailable = false },
        title = { Text(t("update_unavailable_title")) }, text = { Text(t("update_unavailable_body")) },
        confirmButton = { TextButton(onClick = {
            UpdateChecker.openUpdatePage(context, UpdateChecker.UpdateInfo("", UpdateChecker.RELEASES_PAGE, ""))
            updateUnavailable = false
        }) { Text(t("open_github")) } },
        dismissButton = { TextButton(onClick = { updateUnavailable = false }) { Text(t("cancel")) } })
    if (confirmHistoryClear) AlertDialog(onDismissRequest = { confirmHistoryClear = false },
        title = { Text(t("clear_history")) }, text = { Text(t("history_clear_confirm")) },
        confirmButton = { TextButton(onClick = {
            confirmHistoryClear = false
            scope.launch {
                try {
                    AppDatabase.get(context).historyDao().clearAll()
                    Toast.makeText(context, tr(language, "history_cleared"), Toast.LENGTH_SHORT).show()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { Toast.makeText(context, tr(language, "privacy_stays"), Toast.LENGTH_SHORT).show() }
            }
        }) { Text(t("clear")) } },
        dismissButton = { TextButton(onClick = { confirmHistoryClear = false }) { Text(t("cancel")) } })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(t("settings"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text(t("premium_exp"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Dil seçimi
        PremiumSettingCard(
            icon = Icons.Rounded.Language,
            title = t("language"),
            subtitle = t("language_sub"),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.indirgitsin.app.data.lang.AppStrings.supported.forEach { (code, label) ->
                        val sel = language == code
                        FilterChip(
                            selected = sel,
                            onClick = { scope.launch { SettingsStore.setLanguage(context, code) } },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        )

        // Renk seçimi
        PremiumSettingCard(
            icon = Icons.Rounded.Palette,
            title = t("color"),
            subtitle = t("color_sub"),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.indirgitsin.app.ui.theme.AppColor.entries.forEach { ac ->
                        val sel = appColor == ac.key
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ac.primary)
                                .clickable { scope.launch { SettingsStore.setAppColor(context, ac.key) } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        )

        // Tema seçimi
        PremiumSettingCard(
            icon = Icons.Rounded.DarkMode,
            title = t("theme"),
            subtitle = when (theme) { "light" -> t("theme_light"); "dark" -> t("theme_dark"); else -> t("theme_system") },
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("light" to t("light"), "dark" to t("dark"), "system" to t("system")).forEach { (v, label) ->
                        val selected = theme == v
                        FilterChip(
                            selected = selected,
                            onClick = { scope.launch { SettingsStore.setTheme(context, v) } },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        )

        // İndirme Konumu
        PremiumSettingCard(
            icon = Icons.Rounded.Folder,
            title = t("download_location"),
            subtitle = t("download_location_sub", downloadFolder),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        try {
                            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), downloadFolder)
                            if (!dir.exists()) dir.mkdirs()
                            val uri = Uri.parse(dir.absolutePath)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "resource/folder")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(Intent.createChooser(intent, tr(language, "open_folder")))
                        } catch (e: Exception) {
                            Toast.makeText(context, "/Download/$downloadFolder", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(tr(language, "open"), fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { folderInput = downloadFolder; showFolderDialog = true }) {
                        Text(tr(language, "change"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )

        if (showFolderDialog) {
            AlertDialog(
                onDismissRequest = { showFolderDialog = false },
                title = { Text(tr(language, "folder_choose_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(language, "folder_choose_desc"), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = folderInput, onValueChange = { folderInput = it }, singleLine = true, placeholder = { Text("IndirGitsin") })
                        Text(tr(language, "folder_example"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val clean = folderInput.take(30).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "IndirGitsin" }
                            SettingsStore.setDownloadSubfolder(context, clean)
                            Toast.makeText(context, tr(language, "folder_saved", clean), Toast.LENGTH_SHORT).show()
                        }
                        showFolderDialog = false
                    }) { Text(tr(language, "save")) }
                },
                dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text(tr(language, "cancel")) } }
            )
        }

        if (manualUpdate != null) {
            AlertDialog(
                onDismissRequest = { manualUpdate = null },
                title = { Text(tr(language, "update_check_title", manualUpdate!!.latestTag)) },
                text = { Text(manualUpdate!!.body.take(350).ifBlank { tr(language, "no_update_body") }) },
                confirmButton = {
                    TextButton(onClick = {
                        com.indirgitsin.app.util.UpdateChecker.openUpdatePage(context, manualUpdate!!)
                        manualUpdate = null
                    }) { Text(tr(language, "update_btn")) }
                },
                dismissButton = { TextButton(onClick = { manualUpdate = null }) { Text(tr(language, "close")) } }
            )
        }

        PremiumSettingCard(icon = Icons.Rounded.Wifi, title = t("unmetered_title"), subtitle = t("unmetered_sub"),
            action = { Switch(checked = unmetered, onCheckedChange = { enabled ->
                scope.launch { SettingsStore.setUnmetered(context, enabled) }
            }) })

        PremiumSettingCard(icon = Icons.Rounded.Headphones, title = t("background_playback"), subtitle = t("background_playback_desc"),
            action = { Switch(checked = backgroundPlayback, onCheckedChange = { enabled ->
                scope.launch { SettingsStore.setBackgroundPlayback(context, enabled) }
            }) })

        // Varsayılan Kalite
        PremiumSettingCard(
            icon = Icons.Rounded.HighQuality,
            title = t("default_quality"),
            subtitle = if (autoHigh) tr(language, "auto_high_on") else tr(language, "auto_high_off"),
            action = {
                Switch(checked = autoHigh, onCheckedChange = { v ->
                    scope.launch { SettingsStore.setAutoHigh(context, v) }
                    Toast.makeText(context, if (v) tr(language, "auto_high_toast_on") else tr(language, "auto_high_toast_off"), Toast.LENGTH_SHORT).show()
                })
            }
        )

        PremiumSettingCard(icon = Icons.Rounded.FolderDelete, title = t("partial_clear"), subtitle = t("partial_clear_sub"),
            action = { TextButton(onClick = { confirmPartialClear = true }) { Text(t("clear")) } })
        PremiumSettingCard(icon = Icons.Rounded.PrivacyTip, title = t("privacy_policy"), subtitle = t("privacy_sub"),
            action = { TextButton(onClick = { showPrivacy = true }) { Text(t("open")) } })

        // Ses Formatı
        PremiumSettingCard(
            icon = Icons.Rounded.AudioFile,
            title = t("audio_format"),
            subtitle = t("audio_format_sub", audioFormat),
            action = {
                Surface(
                    onClick = {
                        val formats = listOf("M4A", "WEBM", "MP3")
                        val next = formats[(formats.indexOf(audioFormat) + 1).mod(formats.size)]
                        scope.launch { SettingsStore.setAudioFormat(context, next) }
                        Toast.makeText(context, tr(language, "audio_format_toast", next), Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary
                ) {
                    Text(" $audioFormat ", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        )

        // Önbellek Temizleme
        PremiumSettingCard(
            icon = Icons.Rounded.CleaningServices,
            title = t("clear_cache"),
            subtitle = "${t("cache_size")}: ${formatByteSize(cacheSizeBytes)}",
            action = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val oldSize = formatByteSize(cacheSizeBytes)
                            cacheSizeBytes = withContext(Dispatchers.IO) {
                                UpdateChecker.clearCache()
                                context.cacheDir.listFiles()?.filter { it.name != "release-http" }?.forEach { f ->
                                    try { f.deleteRecursively() } catch (_: Exception) {}
                                }
                                calcCacheSizeBytes(context)
                            }
                            Toast.makeText(context, tr(language, "cache_cleared", oldSize), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Hata", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(tr(language, "clear"), fontWeight = FontWeight.Bold) }
            }
        )

        // Gizlilik / Geçmiş
        PremiumSettingCard(
            icon = Icons.Rounded.Shield,
            title = t("privacy"),
            subtitle = t("privacy_sub"),
            action = {
                TextButton(onClick = { confirmHistoryClear = true }) {
                    Text(tr(language, "clear"), fontWeight = FontWeight.Bold)
                }
            }
        )

        // Güncelleme
        PremiumSettingCard(
            icon = Icons.Rounded.SystemUpdate,
            title = t("update"),
            subtitle = t("update_sub", version),
            action = {
                if (checking) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = {
                    checking = true
                    scope.launch {
                        try {
                            when (val result = UpdateChecker.checkDetailed(context)) {
                                is UpdateChecker.CheckResult.Available -> manualUpdate = result.info
                                UpdateChecker.CheckResult.Current -> Toast.makeText(context, tr(language, "latest_version", version), Toast.LENGTH_SHORT).show()
                                UpdateChecker.CheckResult.Unavailable -> updateUnavailable = true
                            }
                        } finally { checking = false }
                    }
                }) { Text(tr(language, "check"), fontWeight = FontWeight.Bold) }
            }
        )

        // Program Hakkında
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(t("about"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                        Text(" v$version ", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    t("about_desc"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eekilinc/indirgitsin"))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "github.com/eekilinc/indirgitsin", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F), contentColor = Color.White)
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr(language, "source_code"), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Lisans
        PremiumSettingCard(
            icon = Icons.Rounded.VerifiedUser,
            title = t("license"),
            subtitle = t("license_sub"),
            action = {
                TextButton(onClick = { showNotices = true }) { Text(tr(language, "see"), fontWeight = FontWeight.Bold) }
            }
        )

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t("copyright", version), fontWeight = FontWeight.Bold)
                    Text(t("copyright_sub"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PremiumSettingCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: @Composable () -> Unit = {}
) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
    }
}
