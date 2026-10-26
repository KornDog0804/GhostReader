package com.korndog.ghostreader

import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val key: String = "",
    val progress: Float = 0f,
    val started: Boolean = false,
    val lastRead: Long = 0L
) {
    val finished: Boolean get() = started && progress >= 0.97f
    val inProgress: Boolean get() = started && !finished
}

/** Best guess at the series a book belongs to, from its title ("... Dungeon Crawler Carl Book 2"). */
internal fun seriesOf(title: String): String {
    Regex("[:\\-–]\\s*(.+?)\\s*,?\\s+(?:book|vol\\.?|volume|#)\\s*\\d+\\b", RegexOption.IGNORE_CASE).find(title)
        ?.let { return it.groupValues[1].trim() }
    Regex("^(.+?)\\s*(?:[,:\\-–]\\s*)?(?:book|vol\\.?|volume|#)?\\s*\\d+\\s*[:\\-–]", RegexOption.IGNORE_CASE).find(title)
        ?.let { return it.groupValues[1].trim().trimEnd(',', '-', ':') }
    Regex("^(.+?)\\s*[-–]\\s*\\d+\\s*$").find(title)?.let { return it.groupValues[1].trim() }
    return ""
}

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
        listOf("▥" to "Library", "⤓" to "Downloads", "⚙" to "Settings").forEachIndexed { index, (icon, label) ->
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
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLong)
            .padding(bottom = 4.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (entry.audio != null && entry.cover != null) {
                // Audiobook art is square: show all of it, never cropped.
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF14101C)), contentAlignment = Alignment.Center) {
                    ShelfCover(entry.cover, entry.title, entry.author, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)), fit = true)
                }
            } else {
                ShelfCover(entry.cover, entry.title, entry.author, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
            }
            if (entry.audio != null) {
                Text(
                    "🎧",
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .padding(bottom = if (entry.inProgress) 5.dp else 0.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            when {
                entry.finished -> Text(
                    "✓",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(50)).background(Color(0xFF6D3FD0)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
                !entry.started -> Text(
                    "NEW",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF6D3FD0)).padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (entry.inProgress) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)).background(Color(0x99000000))
                ) {
                    Box(Modifier.fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.author.isNotBlank() && !entry.author.startsWith("Unknown")) {
            Text(entry.author, color = Color(0xFF9B8DB0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A wide card for the Continue row: cover, title, and how far you are. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: ShelfEntry, onOpen: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF2A1D42), Color(0xFF17111F))))
            .combinedClickable(onClick = onOpen, onLongClick = onLong)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.audio != null && entry.cover != null) {
            ShelfCover(entry.cover, entry.title, entry.author, Modifier.width(64.dp).height(64.dp).clip(RoundedCornerShape(6.dp)), fit = true)
        } else {
            ShelfCover(entry.cover, entry.title, entry.author, Modifier.width(64.dp).height(92.dp).clip(RoundedCornerShape(6.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.author.isNotBlank() && !entry.author.startsWith("Unknown")) {
                Text(entry.author, color = Color(0xFF9B8DB0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A2D52))) {
                Box(Modifier.fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                (if (entry.audio != null) "Listen" else "Read") + "  ·  " + percentText(entry.progress),
                color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun percentText(fraction: Float): String =
    if (fraction < 0.1f) "%.1f%%".format(fraction * 100) else "${(fraction * 100).toInt()}%"

@Composable
private fun EntryGrid(
    entries: List<ShelfEntry>,
    onOpen: (ShelfEntry) -> Unit,
    onLong: (ShelfEntry) -> Unit,
    showHeader: Boolean = false,
    header: @Composable () -> Unit = {}
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showHeader) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(entries, key = { it.key + it.title }) { entry ->
            CoverTile(entry, onOpen = { onOpen(entry) }, onLong = { onLong(entry) })
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
            Box(Modifier.weight(1f)) { EntryGrid(current.second, onOpen, onLong) }
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

private val filterNames = listOf("All", "Books", "Audiobooks", "In progress", "Finished")
private val sortNames = listOf("Recent", "Title", "Author")

/** My Library: Continue row, filters and sorting, cover tiles with progress, and Books/Authors/Series tabs. */
@Composable
internal fun LibraryScreen(
    books: List<Book>,
    audio: List<File>,
    prefs: SharedPreferences,
    revision: Int,
    onOpenBook: (Book) -> Unit,
    onOpenAudio: (File) -> Unit,
    onManage: (ShelfEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }

    val loaded by produceState<List<ShelfEntry>?>(null, books, audio, revision) {
        value = withContext(Dispatchers.IO) {
            fun overrideCover(key: String): Bitmap? =
                CoverStore.overrideFile(context, key).takeIf { it.exists() }?.let { runCatching { decodeBitmap(it.readBytes()) }.getOrNull() }

            val ebooks = books.map { book ->
                val key = book.progressKey
                val meta = runCatching { readVolumeMetadata(book) }.getOrNull()
                val title = prefs.getString("ov_title_$key", null)?.takeIf { it.isNotBlank() } ?: book.title
                val author = prefs.getString("ov_author_$key", null)?.takeIf { it.isNotBlank() } ?: meta?.author.orEmpty()
                val started = prefs.contains(key)
                ShelfEntry(
                    book, null, title, author, overrideCover(key) ?: meta?.cover, 0L, seriesOf(title), key,
                    progress = prefs.getFloat(key + "_pct", 0f), started = started,
                    lastRead = prefs.getLong(key + "_ts", 0L)
                )
            }
            val audios = audio.map { file ->
                val key = file.name
                val info = runCatching { readShelfAudioMetadata(file) }.getOrNull()
                val title = prefs.getString("ov_title_$key", null)?.takeIf { it.isNotBlank() } ?: (info?.title ?: file.nameWithoutExtension)
                val author = prefs.getString("ov_author_$key", null)?.takeIf { it.isNotBlank() } ?: info?.author.orEmpty()
                val duration = info?.durationMs ?: 0L
                val position = prefs.getInt("audio_$key", 0)
                ShelfEntry(
                    null, file, title, author, overrideCover(key) ?: info?.cover, duration, seriesOf(title), key,
                    progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    started = position > 5_000,
                    lastRead = prefs.getLong("ats_$key", 0L)
                )
            }
            audios + ebooks
        }
    }

    val all = loaded.orEmpty()
    val entries = all
        .filter { query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true) }
        .filter {
            when (filter) {
                1 -> it.audio == null
                2 -> it.audio != null
                3 -> it.inProgress
                4 -> it.finished
                else -> true
            }
        }
        .let { list ->
            when (sort) {
                1 -> list.sortedBy { it.title.lowercase() }
                2 -> list.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
                else -> list.sortedByDescending { it.lastRead }
            }
        }
    val continuing = all.filter { it.inProgress }.sortedByDescending { it.lastRead }.take(8)
    val open: (ShelfEntry) -> Unit = { e -> e.book?.let(onOpenBook) ?: e.audio?.let(onOpenAudio) }
    val long: (ShelfEntry) -> Unit = { e -> onManage(e) }

    Column(Modifier.fillMaxSize().background(screenBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My Library", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                if (loaded != null) {
                    val b = all.count { it.audio == null }
                    val a = all.count { it.audio != null }
                    Text("$b books  ·  $a audiobooks", color = Color(0xFF9B8DB0), fontSize = 12.sp)
                }
            }
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
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = accent) {
            listOf("Books", "Authors", "Series").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, fontWeight = FontWeight.SemiBold) })
            }
        }
        // Filter chips and sort.
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(filterNames.size) { index ->
                val on = filter == index
                Text(
                    filterNames[index],
                    color = if (on) Color.White else Color(0xFFB9ABCE),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Color(0xFF6D3FD0) else Color(0xFF1B1426))
                        .clickable { filter = index }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
            item {
                Text(
                    "Sort: ${sortNames[sort]} ⇅",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Color(0xFF3A2D52), RoundedCornerShape(50))
                        .clickable { sort = (sort + 1) % sortNames.size }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
                tab == 0 -> if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                all.isEmpty() -> "Your library is waiting. Open Downloads to add books."
                                else -> "Nothing here yet. Try another filter or search."
                            },
                            color = Color(0xFF9B8DB0),
                            textAlign = TextAlign.Center
                        )
                    }
                } else EntryGrid(entries, open, long, showHeader = continuing.isNotEmpty() && filter == 0 && query.isBlank()) {
                    Column {
                        Text("Continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(continuing, key = { it.key }) { e -> ContinueCard(e, onOpen = { open(e) }, onLong = { long(e) }) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Everything", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
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
