package com.korndog.ghostreader

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.security.MessageDigest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import java.util.zip.ZipFile
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val accent = Color(0xFFB784FF)
private val screenBg = Color(0xFF07050B)
private val panel = Color(0xFF21192D)

/** One thing on the shelf: an ebook (or one book of an anthology) or an audiobook. */
internal data class ShelfEntry(
    val book: Book?,
    val audio: File?,
    val title: String,
    val author: String,
    val cover: Bitmap?,
    val durationMs: Long,
    val series: String,
    val pdf: File? = null,
    val comic: File? = null
)

/** Best guess at the series a book belongs to, from its title ("... Dungeon Crawler Carl Book 2"). */
internal fun seriesOf(title: String): String {
    Regex("[:\\-–]\\s*(.+?)\\s*,?\\s+(?:book|vol\\.?|volume|#)\\s*\\d+\\b", RegexOption.IGNORE_CASE).find(title)
        ?.let { return it.groupValues[1].trim() }
    Regex("^(.+?)\\s*(?:[,:\\-–]\\s*)?(?:book|vol\\.?|volume|#)?\\s*\\d+\\s*[:\\-–]", RegexOption.IGNORE_CASE).find(title)
        ?.let { return it.groupValues[1].trim().trimEnd(',', '-', ':') }
    Regex("^(.+?)\\s*[-–]\\s*\\d+\\s*$").find(title)?.let { return it.groupValues[1].trim() }
    return ""
}

/** Canonical series order. Other books keep their alphabetical order. */
private val dungeonCarlOrder = listOf(
    "Dungeon Crawler Carl",
    "Carl's Doomsday Scenario",
    "The Dungeon Anarchist's Cookbook",
    "The Gate of the Feral Gods",
    "The Butcher's Masquerade",
    "The Eye of the Bedlam Bride",
    "This Inevitable Ruin",
    "A Parade of Horribles"
)
private fun shelfOrder(entry: ShelfEntry): Pair<Int, String> {
    // Compare letters and digits only, so a lost or curly apostrophe ("Butcher s") still matches.
    fun squash(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")
    val title = squash(entry.title)
    val index = dungeonCarlOrder.indexOfFirst { title.startsWith(squash(it)) }
    return if (index >= 0) Pair(index, "") else Pair(100, entry.title.lowercase())
}
private fun orderedShelf(entries: List<ShelfEntry>) =
    entries.sortedWith(compareBy<ShelfEntry> { shelfOrder(it).first }.thenBy { shelfOrder(it).second })

// ---------------------------------------------------------------- home

/** The front door: the GhostReader art with My Library and Add Books. */
@Composable
internal fun HomeSplash(onLibrary: () -> Unit, onAddBooks: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF010004))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ghost_logo),
                contentDescription = "GhostReader",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(16.dp))
        HomeButton("My Library", filled = true, onClick = onLibrary)
        Spacer(Modifier.height(12.dp))
        HomeButton("Add Books", filled = false, onClick = onAddBooks)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HomeButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) Color(0xFF6D3FD0) else Color(0xFF14101C))
            .then(if (filled) Modifier else Modifier.border(1.dp, Color(0xFF3A2D52), RoundedCornerShape(12.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("›", color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------- bottom bar

@Composable
internal fun ShellNav(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0E0A14), modifier = Modifier.navigationBarsPadding()) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = accent,
            selectedTextColor = accent,
            indicatorColor = Color(0xFF241A35),
            unselectedIconColor = Color(0xFF8E80A3),
            unselectedTextColor = Color(0xFF8E80A3)
        )
        listOf("▥" to "Library", "⤓" to "Downloads", "⌕" to "Discover", "⚙" to "Settings").forEachIndexed { index, (icon, label) ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Text(icon, fontSize = 20.sp) },
                label = { Text(label) },
                colors = colors
            )
        }
    }
}

// ---------------------------------------------------------------- library

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(entry: ShelfEntry, onOpen: () -> Unit, onLong: () -> Unit) {
    val context = LocalContext.current
    // Load document artwork only when a tile is visible. Never decode the entire
    // PDF/CBZ collection while opening My Library.
    val documentCover by produceState<Bitmap?>(null, entryId(entry), entry.cover) {
        value = entry.cover ?: withContext(Dispatchers.IO) {
            runCatching { cachedDocumentCover(context, entry) }.getOrNull()
        }
    }
    val displayEntry = if (documentCover != null && entry.cover == null) entry.copy(cover = documentCover) else entry
    val (shownTitle, subline) = remember(entry.title, entry.author, entry.series) { tileText(entry) }
    // Audiobook art is square. Tint the card from the cover's own average colour so the
    // spare space above and below looks designed instead of empty.
    val tint = remember(displayEntry.cover) {
        displayEntry.cover?.let { bmp ->
            runCatching { richBackdrop(Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)) }.getOrNull()
        }
    }
    val badge = when { entry.audio != null -> "🎧"; entry.comic != null -> "CBZ"; entry.pdf != null -> "PDF"; else -> null }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLong)
            .padding(bottom = 4.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
            if (entry.audio != null && displayEntry.cover != null) {
                val backdrop = tint ?: Color(0xFF14101C)
                Box(Modifier.fillMaxSize().background(backdrop), contentAlignment = Alignment.Center) {
                    ShelfCover(displayEntry.cover, entry.title, entry.author, Modifier.fillMaxWidth().aspectRatio(1f), fit = true)
                }
            } else {
                ShelfCover(displayEntry.cover, entry.title, entry.author, Modifier.fillMaxSize())
            }
            if (entry.audio != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp)
                        .background(Color(0x990E0B14), CircleShape),
                    contentAlignment = Alignment.Center
                ) { HeadphonesIcon(Color.White, Modifier.size(11.dp)) }
            } else if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(Color(0x990E0B14), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // Thin progress bar along the bottom edge. Read-only: shows nothing until you've started.
            val progress = ShelfProgress.fraction(context, entry)
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x990E0B14))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(if (progress >= 0.98f) Color(0xFF58D68D) else accent))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        // minLines keeps every tile's text block the same height so rows stay aligned.
        Text(shownTitle, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subline, color = Color(0xFF9B8DB0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
    }
}

/** Player heading: "The Dungeon Anarchist's Cookbook: Dungeon Crawler Carl, Book 3" -> title + "Dungeon Crawler Carl · Book 3". */
internal fun splitAudioTitle(rawTitle: String): Pair<String, String> {
    val raw = rawTitle.replace(Regex("(?<=\\p{L}) s(?=\\s|$)"), "'s").replace(Regex("\\s+"), " ").trim()
    val head = raw.substringBefore(':').trim()
    val hasTail = ':' in raw && head.length >= 3
    val title = (if (hasTail) head else raw).replace(Regex("(?i)[,\\s]+(?:book|vol\\.?|volume)\\s*\\d{1,3}\\s*$"), "").trim().ifBlank { raw }
    val tail = if (hasTail) raw.substringAfter(':').trim() else Regex("(?i)(?:book|vol\\.?|volume)\\s*\\d{1,3}\\s*$").find(raw)?.value.orEmpty()
    val sub = tail.replace(Regex("(?i)\\s*,?\\s*(book|vol\\.?|volume)\\s*(\\d{1,3})"), " · Book $2").trim(' ', '·').trim()
    return title to sub
}

/** Dark, saturated version of a cover's average colour; neutral/white covers get the app's purple. */
private fun richBackdrop(argb: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    if (hsv[1] < 0.15f) { hsv[0] = 265f; hsv[1] = 0.35f } else hsv[1] = hsv[1].coerceIn(0.40f, 0.75f)
    hsv[2] = 0.22f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Small drawn headphones, so the badge never depends on emoji fonts. */
@Composable
private fun HeadphonesIcon(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = w * 0.14f
        drawArc(color, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(w - stroke, h * 1.1f - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(0f, h * 0.52f),
            androidx.compose.ui.geometry.Size(w * 0.26f, h * 0.46f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.52f),
            androidx.compose.ui.geometry.Size(w * 0.26f, h * 0.46f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
    }
}

/**
 * Display-only title and second line for a tile. Never used for file names or progress keys.
 * "Carl's Doomsday Scenario: Dungeon Crawler Carl Book 2" -> "Carl's Doomsday Scenario" / "Matt Dinniman".
 */
internal fun tileText(entry: ShelfEntry): Pair<String, String> {
    val raw = entry.title
        .replace(Regex("(?<=\\p{L}) s(?=\\s|$)"), "'s") // "Butcher s Masquerade" -> "Butcher's Masquerade"
        .replace(Regex("\\s+"), " ").trim()
    val number = Regex("(?i)(?:book|vol\\.?|volume|#)\\s*(\\d{1,3})\\b").find(raw)?.groupValues?.get(1)
    val head = raw.substringBefore(':').trim()
    var title = if (':' in raw && head.length >= 3) head else raw
    title = title.replace(Regex("(?i)[,\\s]+(?:book|vol\\.?|volume)\\s*\\d{1,3}\\s*$"), "").trim().ifBlank { raw }
    val author = entry.author.takeIf { it.isNotBlank() && !it.startsWith("Unknown") }
    // Skip a series/number the title already shows (e.g. comics named "Batman #12").
    val series = entry.series.takeIf { it.isNotBlank() && !title.startsWith(it, ignoreCase = true) }
    val numberShown = number != null && Regex("(?<!\\d)$number(?!\\d)").containsMatchIn(title)
    val line = when {
        author != null -> author
        series != null && number != null && !numberShown -> "$series · Book $number"
        series != null -> series
        number != null && !numberShown -> "Book $number"
        else -> ""
    }
    return title to line.ifBlank { " " }
}

@Composable
private fun EntryGrid(entries: List<ShelfEntry>, onOpen: (ShelfEntry) -> Unit, onLong: (ShelfEntry) -> Unit, header: (@Composable () -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (header != null) item(key = "header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        items(entries, key = { entryId(it) }) { entry ->
            CoverTile(entry, onOpen = { onOpen(entry) }, onLong = { onLong(entry) })
        }
    }
}

/** "Continue" strip: what you've started and not finished, most recent first. Read-only. */
@Composable
private fun ContinueRow(items: List<Pair<ShelfEntry, Float>>, onOpen: (ShelfEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text("Continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 8.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(items, key = { entryId(it.first) }) { (entry, fraction) -> ContinueCard(entry, fraction) { onOpen(entry) } }
        }
    }
}

@Composable
private fun ContinueCard(entry: ShelfEntry, fraction: Float, onOpen: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState<Bitmap?>(null, entryId(entry), entry.cover) {
        value = entry.cover ?: withContext(Dispatchers.IO) { runCatching { cachedDocumentCover(context, entry) }.getOrNull() }
    }
    val (title, _) = remember(entry.title) { tileText(entry) }
    val detail = if (entry.audio != null && entry.durationMs > 0) {
        val left = (entry.durationMs * (1f - fraction)).toLong() / 60_000L
        (if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left")
    } else "${(fraction * 100).toInt()}% read"
    Row(
        Modifier
            .width(236.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1426))
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShelfCover(cover, entry.title, entry.author, Modifier.size(width = 52.dp, height = 78.dp).clip(RoundedCornerShape(6.dp)), fit = entry.audio != null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, color = Color(0xFF9B8DB0), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3050))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent))
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<Pair<String, List<ShelfEntry>>>,
    emptyText: String,
    onOpen: (ShelfEntry) -> Unit,
    onLong: (ShelfEntry) -> Unit
) {
    var open by remember { mutableStateOf<String?>(null) }
    val current = groups.firstOrNull { it.first == open }
    if (current != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = null }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = accent, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text(current.first, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${current.second.size}", color = Color(0xFF9B8DB0))
            }
            Box(Modifier.weight(1f)) { EntryGrid(orderedShelf(current.second), onOpen, onLong) }
        }
    } else if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = Color(0xFF9B8DB0), textAlign = TextAlign.Center)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(groups, key = { it.first }) { (name, list) ->
                Row(
                    Modifier.fillMaxWidth().clickable { open = name }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-18).dp)) {
                        list.take(3).forEach { e ->
                            ShelfCover(
                                e.cover, e.title, e.author,
                                Modifier.width(44.dp).height(64.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, screenBg, RoundedCornerShape(5.dp))
                            )
                        }
                    }
                    Spacer(Modifier.width(if (list.size > 1) 30.dp else 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${list.size} ${if (list.size == 1) "title" else "titles"}", color = Color(0xFF9B8DB0), fontSize = 12.sp)
                    }
                    Text("›", color = Color(0xFF9B8DB0), fontSize = 20.sp)
                }
            }
        }
    }
}

private fun entryId(entry: ShelfEntry): String =
    entry.book?.let { "book:" + it.file.absolutePath + "#" + it.sectionPrefix }
        ?: entry.audio?.let { "audio:" + it.absolutePath }
        ?: entry.pdf?.let { "pdf:" + it.absolutePath }
        ?: "comic:" + entry.comic!!.absolutePath

private fun manualCoverFile(context: android.content.Context, entry: ShelfEntry): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(entryId(entry).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return File(File(context.filesDir, "manual_covers").apply { mkdirs() }, "$digest.jpg")
}

/**
 * Persistent, size-bounded document thumbnails. Manual covers always win.
 * Cache keys include file size and modification time so replaced documents
 * cannot silently keep old artwork.
 */
// Presentation-only cleanup. Keep original filenames and file paths untouched.
private fun readableDocumentTitle(file: File, comic: Boolean): String {
    // Display names only: never rename files or disturb reading progress.
    var title = file.nameWithoutExtension
        .replace(Regex("^\\d{10,17}_"), "")
        .replace('_', ' ')
        .replace(Regex("(?i)^(?:www\\.)?oceanofpdf(?:\\.com)?[\\s._-]*"), "")
        .replace(Regex("(?i)\\s*\\((?:digital|empire|kileko[- ]empire|scan|scanned|webrip|retail|20\\d{2})\\)"), " ")
        .replace(Regex("(?i)\\s*\\[(?:digital|scan|retail|20\\d{2})\\]"), " ")
        .replace(Regex("(?i)\\s+(?:digital|kileko[- ]empire|empire)\\b.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '.', '_')
    if (comic) {
        val issue = Regex("(?i)^(.+?)\\s+#?0*(\\d{1,4})(?:\\s*\\(?20\\d{2}\\)?)?$").matchEntire(title)
        if (issue != null) {
            title = "ISSUE_TITLE #ISSUE_NUMBER"
                .replace("ISSUE_TITLE", issue.groupValues[1].trim())
                .replace("ISSUE_NUMBER", issue.groupValues[2].toInt().toString())
        }
    }
    return title.trim().ifBlank { if (comic) "Comic" else "PDF Document" }
}

private fun cachedDocumentCover(context: android.content.Context, entry: ShelfEntry): Bitmap? {
    val file = entry.pdf ?: entry.comic ?: return null
    if (!file.isFile) return null
    val id = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val folder = File(context.cacheDir, "document_covers_v2").apply { mkdirs() }
    val target = File(folder, "$digest.jpg")
    fun decode(): Bitmap? = BitmapFactory.decodeFile(target.absolutePath)
    if (target.isFile) decode()?.let { return it }
    val image = if (entry.pdf != null) pdfCover(file) else comicFirstPageCover(file)
    if (image != null) {
        val temp = File(folder, "$digest.tmp")
        try {
            temp.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
        } finally { temp.delete() }
    }
    return image
}

private fun comicFirstPageCover(file: File): Bitmap? = ZipFile(file).use { zip ->
    val first = zip.entries().asSequence()
        .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") &&
            !it.name.split('/').any { part -> part.startsWith(".") } &&
            it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {
            it.name.replace(Regex("\\d+")) { m -> m.value.padStart(12, '0') }
        }).firstOrNull() ?: return@use null
    if (first.size > 35_000_000L) return@use null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    zip.getInputStream(first).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
    val sample = sharpSampleSize(bounds.outWidth, bounds.outHeight, 540, 810)
    zip.getInputStream(first).use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}

/** My Library: cover tiles with Books, Authors and Series tabs, and search. */
@Composable
internal fun LibraryScreen(
    books: List<Book>,
    audio: List<File>,
    pdfs: List<File>,
    comics: List<File>,
    imageBooks: List<ImageFolderBook>,
    onOpenImageBook: (ImageFolderBook) -> Unit,
    onRemoveImageBook: (ImageFolderBook) -> Unit,
    onOpenComic: (File) -> Unit,
    onDeleteComic: (File) -> Unit,
    onOpenPdf: (File) -> Unit,
    onDeletePdf: (File) -> Unit,
    onOpenBook: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onOpenAudio: (File) -> Unit,
    onDeleteAudio: (File) -> Unit
) {
    val context = LocalContext.current
    val orderPrefs = remember { context.getSharedPreferences("ghostreader_shelf_order", android.content.Context.MODE_PRIVATE) }
    var manualOrder by remember { mutableStateOf(
        runCatching {
            val array = JSONArray(orderPrefs.getString("order", "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    ) }
    var menuEntry by remember { mutableStateOf<ShelfEntry?>(null) }
    var exportSource by remember { mutableStateOf<File?>(null) }
    var cloudMsg by remember { mutableStateOf<String?>(null) }
    var cloudBusy by remember { mutableStateOf(false) }
    val cloudScope = androidx.compose.runtime.rememberCoroutineScope()
    var exportCover by remember { mutableStateOf<Bitmap?>(null) }
    var exportCoverFile by remember { mutableStateOf<File?>(null) }
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    // "Save this one": one book, comic, PDF or audiobook plus its progress and bookmarks, checked after saving.
    var saveOneEntry by remember { mutableStateOf<ShelfEntry?>(null) }
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val target = saveOneEntry
        saveOneEntry = null
        if (uri != null && target != null) {
            val (kind, file) = when {
                target.book != null -> BackupKind.BOOKS to target.book.file
                target.audio != null -> BackupKind.AUDIO to target.audio
                target.pdf != null -> BackupKind.PDFS to target.pdf
                else -> BackupKind.COMICS to target.comic!!
            }
            val cover = manualCoverFile(context, target).takeIf { it.exists() }
            android.widget.Toast.makeText(context, "Saving \u201c${target.title}\u201d. Keep this screen open.", android.widget.Toast.LENGTH_SHORT).show()
            exportScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { LibraryBackup.exportOne(context.applicationContext, kind, file, cover, target.title, uri) }
                }
                android.widget.Toast.makeText(context,
                    result.fold({ it.summary() }, { "Save failed: ${it.message}" }),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val audioExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) {
            exportScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        require(source.isFile && source.canRead()) { "Original audiobook file is unavailable" }
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            source.inputStream().buffered().use { input -> input.copyTo(output, 128 * 1024) }
                            output.flush()
                        } ?: error("Could not open destination")
                    }
                }
                android.widget.Toast.makeText(
                    context,
                    if (result.isSuccess) "Audiobook copy saved. Import it in GhostKernel." else "Export failed: ${result.exceptionOrNull()?.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val coverExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val bitmap = exportCover
        val originalFile = exportCoverFile
        exportCover = null
        exportCoverFile = null
        if (uri != null && bitmap != null) {
            exportScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            if (originalFile != null && originalFile.isFile) {
                                // Your chosen picture, byte for byte: no resize, no second JPEG pass.
                                originalFile.inputStream().use { it.copyTo(output) }
                            } else {
                                require(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, output)) { "Could not encode cover" }
                            }
                        } ?: error("Could not open destination")
                    }
                }
                android.widget.Toast.makeText(context,
                    if (result.isSuccess) "Cover saved. Choose it in GhostKernel's Change cover menu."
                    else "Cover export failed: ${result.exceptionOrNull()?.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    var coverTarget by remember { mutableStateOf<ShelfEntry?>(null) }
    var artworkRevision by remember { mutableIntStateOf(0) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = coverTarget
        if (uri != null && target != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read image")
                require(bytes.size <= 20_000_000) { "Image exceeds 20 MB" }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Invalid image")
                val destination = manualCoverFile(context, target)
                val temporary = File(destination.parentFile, destination.name + ".tmp")
                try {
                    temporary.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Image conversion failed" }
                    }
                    if (!temporary.renameTo(destination)) temporary.copyTo(destination, overwrite = true)
                } finally { temporary.delete() }
                artworkRevision++
            }.onFailure {
                android.widget.Toast.makeText(context, it.message ?: "Could not save cover", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        coverTarget = null
    }
    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val loaded by produceState<List<ShelfEntry>?>(null, books, audio, pdfs, comics, artworkRevision) {
        value = withContext(Dispatchers.IO) {
            val ebooks = books.map { book ->
                val meta = runCatching { readVolumeMetadata(book) }.getOrNull()
                ShelfEntry(book, null, book.title, meta?.author.orEmpty(), meta?.cover, 0L, seriesOf(book.title))
            }
            val audios = audio.map { file ->
                val info = runCatching { readShelfAudioMetadata(AudioBookTracks.tracks(file).firstOrNull() ?: file) }.getOrNull()
                val title = if (file.isDirectory) file.name.replace(Regex("^\\d+_"), "").replace('_', ' ') else info?.title ?: file.nameWithoutExtension
                ShelfEntry(null, file, title, info?.author.orEmpty(), info?.cover, info?.durationMs ?: 0L, seriesOf(title))
            }
            val documents = pdfs.map { file ->
                val title = readableDocumentTitle(file, comic = false)
                val cover = null // Defer PDF thumbnail rendering; opening the shelf must not render every PDF
                ShelfEntry(null, null, title, "", cover, 0L, seriesOf(title), pdf=file)
            }
            val comicEntries = comics.map { file ->
                val title = readableDocumentTitle(file, comic = true)
                ShelfEntry(null, null, title, "", null, 0L, seriesOf(title), comic=file)
            }
            (audios + ebooks + documents + comicEntries).let { all ->
                // Books of one known series share an author: fill a missing one from a sibling.
                val seriesAuthor = all.filter { shelfOrder(it).first < 100 && it.author.isNotBlank() && !it.author.startsWith("Unknown") }
                    .groupingBy { it.author }.eachCount().maxByOrNull { it.value }?.key
                if (seriesAuthor == null) all
                else all.map { if (shelfOrder(it).first < 100 && (it.author.isBlank() || it.author.startsWith("Unknown"))) it.copy(author = seriesAuthor) else it }
            }.map { entry ->
                val coverFile = manualCoverFile(context, entry)
                val chosen = if (coverFile.exists()) runCatching { decodeCoverFile(coverFile.absolutePath) }.getOrNull() else null
                if (chosen != null) entry.copy(cover = chosen) else entry
            }
        }
    }

    var mediaFilter by remember { mutableStateOf("All") }
    val entries = loaded.orEmpty().filter {
        (mediaFilter=="All" || (mediaFilter=="Read" && (it.book!=null || it.pdf!=null)) || (mediaFilter=="Audio" && it.audio!=null) || (mediaFilter=="Comics" && it.comic!=null)) &&
        (query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true))
    }
    val orderedEntries = remember(entries, manualOrder) {
        val byId = entries.associateBy(::entryId)
        val pinned = manualOrder.mapNotNull(byId::get)
        val pinnedIds = pinned.map(::entryId).toSet()
        pinned + orderedShelf(entries.filterNot { entryId(it) in pinnedIds })
    }
    fun moveEntry(entry: ShelfEntry, delta: Int) {
        // Capture the entire visible shelf order so one move cannot reshuffle the other titles.
        val ids = orderedEntries.map(::entryId).toMutableList()
        val old = ids.indexOf(entryId(entry))
        if (old < 0) return
        val next = (old + delta).coerceIn(0, ids.lastIndex)
        if (next == old) return
        ids.add(next, ids.removeAt(old))
        manualOrder = ids
        orderPrefs.edit().putString("order", JSONArray(ids).toString()).apply()
    }
    val open: (ShelfEntry) -> Unit = { e -> e.book?.let(onOpenBook) ?: e.audio?.let(onOpenAudio) ?: e.pdf?.let(onOpenPdf) ?: e.comic?.let(onOpenComic) }
    val long: (ShelfEntry) -> Unit = { e -> menuEntry = e }

    cloudMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { if (!cloudBusy) cloudMsg = null },
            title = { Text("Cloudflare") },
            text = { Text(msg + if (cloudBusy) "\n\nKeep this screen open until it finishes." else "") },
            confirmButton = { TextButton(onClick = { cloudMsg = null }, enabled = !cloudBusy) { Text("OK") } }
        )
    }
    menuEntry?.let { entry ->
        val position = orderedEntries.indexOfFirst { entryId(it) == entryId(entry) }
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(entry.title) },
            text = {
                Column {
                    Text("Shelf position ${position + 1} of ${orderedEntries.size}. Arrange books your way.")
                    TextButton(onClick = {
                        coverTarget = entry
                        menuEntry = null
                        coverPicker.launch("image/*")
                    }) { Text("Change cover") }
                    if (manualCoverFile(context, entry).exists()) {
                        TextButton(onClick = {
                            manualCoverFile(context, entry).delete()
                            artworkRevision++
                            menuEntry = null
                        }) { Text("Reset cover to automatic") }
                    }
                    TextButton(onClick = {
                        saveOneEntry = entry
                        menuEntry = null
                        saveOne.launch(entry.title.replace(Regex("[^a-zA-Z0-9 .,'()!-]"), "").trim().ifBlank { "GhostReader item" } + " - GhostReader.zip")
                    }) { Text("Save this one (with progress)") }
                    if (entry.audio?.isFile == true) {
                        TextButton(onClick = {
                            exportSource = entry.audio
                            menuEntry = null
                            audioExport.launch(entry.title.replace(Regex("[^a-zA-Z0-9 .,'()!-]"), "").trim().ifBlank { "Audiobook" } + "." + entry.audio.extension.lowercase())
                        }) { Text("Export audiobook copy") }
                        TextButton(onClick = {
                            val file = entry.audio
                            val title = entry.title
                            val art = entry.cover
                            menuEntry = null
                            cloudBusy = true
                            cloudMsg = "Starting…"
                            val act = context as? android.app.Activity
                            act?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            cloudScope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    runCatching { CloudSync.sendAudiobook(context, file, title, art) { m -> cloudMsg = m } }
                                }
                                act?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                cloudBusy = false
                                cloudMsg = res.getOrElse { "Didn't finish: " + (it.message ?: "connection problem") + ". Tap Send to Cloudflare again to resume." }
                            }
                        }) { Text("Send to Cloudflare") }
                        TextButton(onClick = {
                            val cover = entry.cover
                            if (cover == null) {
                                android.widget.Toast.makeText(context, "No cover available to export", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                exportCover = cover
                                exportCoverFile = manualCoverFile(context, entry).takeIf { it.isFile }
                                menuEntry = null
                                coverExport.launch(entry.title.replace(Regex("[^a-zA-Z0-9 .,'()!-]"), "").trim() + " - cover.jpg")
                            }
                        }, enabled = entry.cover != null) { Text("Export cover for GhostKernel") }
                    }
                    TextButton(onClick = { moveEntry(entry, -1) }, enabled = position > 0) { Text("↑ Move up one") }
                    TextButton(onClick = { moveEntry(entry, 1) }, enabled = position in 0 until orderedEntries.lastIndex) { Text("↓ Move down one") }
                    TextButton(onClick = { moveEntry(entry, -position) }, enabled = position > 0) { Text("Move to beginning") }
                    TextButton(onClick = { moveEntry(entry, orderedEntries.lastIndex - position) }, enabled = position in 0 until orderedEntries.lastIndex) { Text("Move to end") }
                }
            },
            confirmButton = { TextButton(onClick = { menuEntry = null }) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = {
                    menuEntry = null
                    entry.book?.let(onDeleteBook) ?: entry.audio?.let(onDeleteAudio) ?: entry.pdf?.let(onDeletePdf) ?: entry.comic?.let(onDeleteComic)
                }) { Text("Delete") }
            }
        )
    }
    Column(Modifier.fillMaxSize().background(screenBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Library", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                if (searching) "✕" else "⌕",
                color = accent,
                fontSize = 22.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { searching = !searching; if (!searching) query = "" }.padding(12.dp)
            )
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search titles and authors") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Read","Audio","Comics","Image Books").forEach { kind -> FilterChip(selected=mediaFilter==kind,onClick={mediaFilter=kind},label={Text(kind)}) }
        }
        if(mediaFilter!="Image Books") TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = accent) {
            listOf("Books", "Authors", "Series").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, fontWeight = FontWeight.SemiBold) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                mediaFilter=="Image Books" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ImageFolderShelf(imageBooks.filter { query.isBlank() || it.title.contains(query,true) },onOpenImageBook,onRemoveImageBook)
                }
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
                tab == 0 -> if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "Your library is waiting. Open Downloads to add books." else "Nothing matches that search.",
                            color = Color(0xFF9B8DB0),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val continueItems = remember(entries) {
                        if (mediaFilter == "All" && query.isBlank()) ShelfProgress.continueList(context, entries) else emptyList()
                    }
                    EntryGrid(orderedEntries, open, long, header = if (continueItems.isEmpty()) null else ({ ContinueRow(continueItems, open) }))
                }

                tab == 1 -> GroupList(
                    groups = entries.groupBy { it.author.ifBlank { "Unknown author" } }
                        .toList().sortedBy { it.first.lowercase() },
                    emptyText = "No authors yet.",
                    onOpen = open, onLong = long
                )

                else -> GroupList(
                    groups = entries.filter { it.series.isNotBlank() }.groupBy { it.series }
                        .toList().sortedBy { it.first.lowercase() },
                    emptyText = "No series found yet. Books titled like \"Name Book 2\" are grouped automatically.",
                    onOpen = open, onLong = long
                )
            }
        }
    }
}
