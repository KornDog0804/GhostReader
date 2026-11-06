package com.korndog.ghostreader

import android.app.usage.StorageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/*
 * READ-ONLY storage diagnostic.
 *
 * This file must never delete, rename, move, create or write anything. It only calls
 * File.listFiles / length / lastModified, Android's own storage-stats service, and one MediaStore
 * query for backup zips that this app created. Results live in memory and can be copied to the
 * clipboard as text. Nothing is written to disk, so the scan cannot add to the cache it measures.
 */

internal data class DiagRow(
    val group: String,
    val name: String,
    val bytes: Long,
    val files: Int,
    val dirs: Int,
    val oldest: Long,
    val newest: Long,
    val largest: List<Pair<String, Long>>,
    val partial: Boolean,
    val taggedBytes: Long,
    val taggedFiles: Int,
    val gids: String,
    val details: List<Triple<String, Long, Boolean>>,
    val role: String,
    val permanence: String,
    val note: String
)

internal data class DiagSummary(
    val version: String = "",
    val appBytes: Long = -1,
    val dataBytes: Long = -1,
    val cacheBytes: Long = -1,
    val deviceFree: Long = -1,
    val deviceTotal: Long = -1,
    val backups: List<Pair<String, Long>> = emptyList(),
    val entries: Long = 0,
    val elapsedMs: Long = 0,
    val partial: Boolean = false,
    val error: String? = null
)

internal class DiagProgress { val entries = AtomicLong() }

private const val ABANDONED_AFTER_MS = 30L * 60_000L
private const val MAX_ENTRIES = 1_000_000L
private const val MAX_SCAN_MS = 60_000L

private class Budget(val deadline: Long) {
    var entries = 0L
    fun exceeded() = entries >= MAX_ENTRIES || System.currentTimeMillis() > deadline
}

private class Tally {
    var bytes = 0L
    var files = 0
    var dirs = 0
    var oldest = Long.MAX_VALUE
    var newest = 0L
    val largest = ArrayList<Pair<String, Long>>()
    var partial = false
    var taggedBytes = 0L
    var taggedFiles = 0
    val gids = java.util.TreeSet<Int>()
    val details = ArrayList<Triple<String, Long, Boolean>>()
}

// Android counts a file as "cache" when its group id is in the cache range (20000..29999),
// whatever folder it now lives in. stat() only reads metadata.
private fun fileGid(f: File): Int = runCatching { android.system.Os.stat(f.path).st_gid }.getOrDefault(-1)

private fun addFile(t: Tally, root: File, f: File, detail: Boolean) {
    val len = f.length().coerceAtLeast(0L)
    t.bytes += len
    t.files++
    val gid = fileGid(f)
    val tagged = gid in 20000..29999
    if (t.gids.size < 6) t.gids.add(gid)
    if (tagged) { t.taggedBytes += len; t.taggedFiles++ }
    if (detail && t.details.size < 80) t.details.add(Triple(runCatching { f.relativeTo(root).path }.getOrDefault(f.name), len, tagged))
    val m = f.lastModified()
    if (m < t.oldest) t.oldest = m
    if (m > t.newest) t.newest = m
    if (t.largest.size < 5 || len > t.largest.last().second) {
        val label = if (f == root) f.name else runCatching { f.relativeTo(root).path }.getOrDefault(f.name)
        t.largest.add(label to len)
        t.largest.sortByDescending { it.second }
        while (t.largest.size > 5) t.largest.removeAt(t.largest.size - 1)
    }
}

private fun isLink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

/** Iterative walk (no recursion), size and date only. Never opens a file. */
private fun scanTree(root: File, budget: Budget, progress: DiagProgress, detail: Boolean, cancelled: () -> Boolean): Tally {
    val t = Tally()
    if (!root.exists()) return t
    if (!root.isDirectory) { addFile(t, root, root, detail); return t }
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        if (cancelled() || budget.exceeded()) { t.partial = true; break }
        val dir = stack.removeLast()
        t.dirs++
        val kids = dir.listFiles() ?: continue
        for (k in kids) {
            budget.entries++
            progress.entries.incrementAndGet()
            if (budget.entries % 2000L == 0L) Thread.yield()
            if (k.isDirectory) { if (!isLink(k)) stack.addLast(k) } else addFile(t, root, k, detail)
        }
    }
    if (t.files == 0) { t.oldest = root.lastModified(); t.newest = root.lastModified() }
    return t
}

private val torrentDirs = setOf("ghostreader-audio-torrent", "ghostreader-comic-torrent", "ghostreader-torrent")

private fun classify(group: String, name: String): Pair<String, String> = when {
    group == "cache" && name in torrentDirs -> "Torrent download work folder" to "disposable"
    group == "cache" && name.startsWith("ghostreader-restore-") -> "Backup restore staging" to "disposable"
    group == "cache" && name == "ghostreader-export.zip" -> "Backup export staging" to "disposable"
    group == "cache" && name == "document_covers" -> "Cover thumbnails" to "disposable"
    group == "cache" -> "Unrecognised cache item (possibly a library or image loader)" to "disposable"
    group == "files" && name in setOf("books", "audio", "comics", "pdfs") -> "Library files" to "PERMANENT"
    group == "files" && name in setOf("covers", "manual_covers") -> "Cover art" to "PERMANENT"
    group == "files" -> "Other app file" to "unknown"
    else -> "Other app data" to "unknown"
}

internal fun fmtBytes(b: Long): String = when {
    b < 0 -> "n/a"
    b >= 1_000_000_000L -> "%.2f GB".format(b / 1e9)
    b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
    b >= 1_000L -> "%.0f KB".format(b / 1e3)
    else -> "$b B"
}

private fun ago(ms: Long): String {
    val m = ms / 60_000L
    return when {
        m < 1 -> "just now"
        m < 90 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}

internal suspend fun runDiagnostic(context: Context, progress: DiagProgress, onRow: suspend (DiagRow) -> Unit): DiagSummary {
    val started = System.currentTimeMillis()
    val job = coroutineContext[Job]
    val budget = Budget(started + MAX_SCAN_MS)
    var partial = false
    withContext(Dispatchers.IO) {
        val thread = Thread.currentThread()
        val oldPriority = thread.priority
        thread.priority = Thread.MIN_PRIORITY
        try {
            val targets = ArrayList<Triple<String, String, File>>()
            fun expand(group: String, dir: File?) {
                dir?.listFiles()?.sortedBy { it.name }?.forEach { targets.add(Triple(group, it.name, it)) }
            }
            expand("cache", context.cacheDir)
            expand("files", context.filesDir)
            expand("external cache", context.externalCacheDir)
            expand("external files", context.getExternalFilesDir(null))
            context.dataDir.listFiles()?.filter { it.name != "cache" && it.name != "files" }?.sortedBy { it.name }
                ?.forEach { targets.add(Triple("app data", it.name, it)) }
            for ((group, name, file) in targets) {
                if (job?.isActive == false) break
                val tally = scanTree(file, budget, progress, group == "files") { job?.isActive == false }
                if (tally.partial) partial = true
                val (role, permanence) = classify(group, name)
                val staging = role.contains("staging") || role.contains("work folder")
                val age = System.currentTimeMillis() - tally.newest
                val note = when {
                    !staging || tally.bytes == 0L -> ""
                    age > ABANDONED_AFTER_MS -> "APPEARS ABANDONED: nothing written for ${ago(age)}"
                    else -> "Recently modified: may be an active job"
                }
                val row = DiagRow(
                    group, name, tally.bytes, tally.files, tally.dirs, tally.oldest, tally.newest,
                    tally.largest.toList(), tally.partial, tally.taggedBytes, tally.taggedFiles, tally.gids.joinToString(","), tally.details.toList(), role, permanence, note
                )
                withContext(Dispatchers.Main) { onRow(row) }
                if (budget.exceeded()) { partial = true; break }
            }
        } finally {
            thread.priority = oldPriority
        }
    }
    var app = -1L; var data = -1L; var cache = -1L; var free = -1L; var total = -1L
    runCatching {
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
        app = st.appBytes; data = st.dataBytes; cache = st.cacheBytes
        free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
        total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
    }
    val backups = ArrayList<Pair<String, Long>>()
    if (Build.VERSION.SDK_INT >= 29) runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("GhostReader-Library-%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c -> while (c.moveToNext() && backups.size < 200) backups.add(c.getString(0) to c.getLong(1)) }
    }
    val version = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        "${p.versionName} (code ${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()}), Android API ${Build.VERSION.SDK_INT}"
    }.getOrDefault("unknown")
    return DiagSummary(version, app, data, cache, free, total, backups, budget.entries, System.currentTimeMillis() - started, partial)
}

internal fun buildReport(rows: List<DiagRow>, s: DiagSummary): String {
    val now = System.currentTimeMillis()
    val sb = StringBuilder()
    sb.append("GhostReader storage diagnostic\n")
    sb.append("Version: ${s.version}\n")
    sb.append("Scan: ${s.entries} entries in ${s.elapsedMs / 1000.0}s${if (s.partial) " (PARTIAL: hit a safety cap)" else ""}\n")
    sb.append("Android reports: app ${fmtBytes(s.appBytes)}, data ${fmtBytes(s.dataBytes)} (includes cache), cache ${fmtBytes(s.cacheBytes)}\n")
    sb.append("Device: free ${fmtBytes(s.deviceFree)} of ${fmtBytes(s.deviceTotal)}\n")
    sb.append("Walked totals by group: ")
    sb.append(rows.groupBy { it.group }.map { (g, r) -> "$g ${fmtBytes(r.sumOf { it.bytes })}" }.joinToString(", "))
    sb.append("\nFiles Android counts as cache: ${fmtBytes(rows.sumOf { it.taggedBytes })} total, of which outside the cache folder: ${fmtBytes(rows.filter { it.group != "cache" }.sumOf { it.taggedBytes })}\n\n")
    rows.sortedWith(compareBy({ groupOrder(it.group) }, { -it.bytes })).forEach { r ->
        sb.append("[${r.group}] ${r.name} | ${fmtBytes(r.bytes)} | ${r.files} files, ${r.dirs} folders")
        sb.append(" | newest ${ago(now - r.newest)}, oldest ${ago(now - r.oldest)}")
        sb.append(" | ${r.role} (${r.permanence})")
        if (r.note.isNotBlank()) sb.append(" | ${r.note}")
        if (r.partial) sb.append(" | PARTIAL")
        sb.append(" | counted as cache by Android: ${fmtBytes(r.taggedBytes)} (${r.taggedFiles} files) gids=${r.gids}")
        sb.append('\n')
        if (r.details.isNotEmpty()) r.details.forEach { sb.append("    ${if (it.third) "CACHE-TAGGED" else "data        "} ${fmtBytes(it.second)}  ${it.first}\n") }
        else r.largest.take(3).forEach { sb.append("    largest: ${it.first} ${fmtBytes(it.second)}\n") }
    }
    sb.append("\nBackup copies in Downloads made by this app: ${s.backups.size} files, ${fmtBytes(s.backups.sumOf { it.second })}\n")
    s.backups.take(10).forEach { sb.append("    ${it.first} ${fmtBytes(it.second)}\n") }
    s.error?.let { sb.append("\nError: $it\n") }
    return sb.toString()
}

private fun groupOrder(g: String) = when (g) { "cache" -> 0; "files" -> 1; "app data" -> 2; else -> 3 }

@Composable
internal fun StorageDiagnosticScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var run by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<DiagRow>>(emptyList()) }
    var summary by remember { mutableStateOf<DiagSummary?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var live by remember { mutableLongStateOf(0L) }
    var copied by remember { mutableStateOf(false) }
    val progress = remember { DiagProgress() }
    BackHandler { onClose() }

    LaunchedEffect(run) {
        scanning = true; rows = emptyList(); summary = null; copied = false
        progress.entries.set(0)
        val ticker = launch { while (true) { live = progress.entries.get(); delay(250) } }
        try {
            summary = runDiagnostic(context, progress) { rows = rows + it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            summary = DiagSummary(error = e.message ?: e.toString())
        } finally {
            ticker.cancel(); live = progress.entries.get(); scanning = false
        }
    }

    val ink = Color(0xFFEDE7F6)
    val dim = Color(0xFFB8AAC9)
    val violet = Color(0xFFB28DFF)
    val warn = Color(0xFFFFB74D)
    Column(Modifier.fillMaxSize().background(Color(0xFF0F0B16)).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose) { Text("← Back") }
            Row {
                TextButton(enabled = !scanning, onClick = { run++ }) { Text("Rescan") }
                TextButton(enabled = !scanning && summary != null, onClick = {
                    val text = buildReport(rows, summary!!)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("GhostReader storage report", text))
                    copied = true
                }) { Text(if (copied) "Copied ✓" else "Copy report") }
            }
        }
        Text("Storage diagnostic", color = Color.White, fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Text("Read-only. Nothing is deleted, moved or written.", color = dim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (scanning) {
            Text("Scanning… $live entries so far", color = violet)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val s = summary
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s != null) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1428)).padding(12.dp)) {
                    s.error?.let { Text("Error: $it", color = warn) }
                    Text("Android says: app ${fmtBytes(s.appBytes)} · data ${fmtBytes(s.dataBytes)} · cache ${fmtBytes(s.cacheBytes)}", color = ink, fontWeight = FontWeight.Bold)
                    Text("Walked: " + rows.groupBy { it.group }.map { (g, r) -> "$g ${fmtBytes(r.sumOf { it.bytes })}" }.joinToString(" · "), color = dim)
                    Text("Phone free: ${fmtBytes(s.deviceFree)} of ${fmtBytes(s.deviceTotal)}", color = dim)
                    Text("${s.entries} entries in ${"%.1f".format(s.elapsedMs / 1000.0)} s${if (s.partial) " · PARTIAL (safety cap reached)" else ""}", color = dim)
                    Text("Backup zips in Downloads from this app: ${s.backups.size} · ${fmtBytes(s.backups.sumOf { it.second })}", color = dim)
                }
            }
            items(rows.sortedWith(compareBy({ groupOrder(it.group) }, { -it.bytes }))) { r ->
                val now = System.currentTimeMillis()
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1428)).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${r.group} / ${r.name}", color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(fmtBytes(r.bytes), color = violet, fontWeight = FontWeight.Bold)
                    }
                    Text("${r.role} · ${r.permanence}", color = dim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Text("${r.files} files · ${r.dirs} folders · newest ${ago(now - r.newest)} · oldest ${ago(now - r.oldest)}", color = dim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    if (r.note.isNotBlank()) Text(r.note, color = warn, fontWeight = FontWeight.Bold)
                    if (r.partial) Text("Partial scan", color = warn)
                    if (r.taggedBytes > 0) Text("Android counts ${fmtBytes(r.taggedBytes)} (${r.taggedFiles} files) of this as CACHE", color = warn, fontWeight = FontWeight.Bold)
                    r.largest.take(3).forEach { Text("  ${it.first} · ${fmtBytes(it.second)}", color = dim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
