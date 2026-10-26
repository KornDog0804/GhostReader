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
    val series: String
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
            ShelfCover(entry.cover, entry.title, entry.author, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
            if (entry.audio != null) {
                Text(
                    "🎧",
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.author.isNotBlank() && !entry.author.startsWith("Unknown")) {
            Text(entry.author, color = Color(0xFF9B8DB0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EntryGrid(entries: List<ShelfEntry>, onOpen: (ShelfEntry) -> Unit, onLong: (ShelfEntry) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(entries, key = { (it.book?.progressKey ?: it.audio?.absolutePath).orEmpty() + it.title }) { entry ->
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

/** My Library: cover tiles with Books, Authors and Series tabs, and search. */
@Composable
internal fun LibraryScreen(
    books: List<Book>,
    audio: List<File>,
    onOpenBook: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onOpenAudio: (File) -> Unit,
    onDeleteAudio: (File) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val loaded by produceState<List<ShelfEntry>?>(null, books, audio) {
        value = withContext(Dispatchers.IO) {
            val ebooks = books.map { book ->
                val meta = runCatching { readVolumeMetadata(book) }.getOrNull()
                ShelfEntry(book, null, book.title, meta?.author.orEmpty(), meta?.cover, 0L, seriesOf(book.title))
            }
            val audios = audio.map { file ->
                val info = runCatching { readShelfAudioMetadata(file) }.getOrNull()
                val title = info?.title ?: file.nameWithoutExtension
                ShelfEntry(null, file, title, info?.author.orEmpty(), info?.cover, info?.durationMs ?: 0L, seriesOf(title))
            }
            audios + ebooks
        }
    }

    val entries = loaded.orEmpty().filter {
        query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true)
    }
    val open: (ShelfEntry) -> Unit = { e -> e.book?.let(onOpenBook) ?: e.audio?.let(onOpenAudio) }
    val long: (ShelfEntry) -> Unit = { e -> e.book?.let(onDeleteBook) ?: e.audio?.let(onDeleteAudio) }

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
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = accent) {
            listOf("Books", "Authors", "Series").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, fontWeight = FontWeight.SemiBold) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
                tab == 0 -> if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "Your library is waiting. Open Downloads to add books." else "Nothing matches that search.",
                            color = Color(0xFF9B8DB0),
                            textAlign = TextAlign.Center
                        )
                    }
                } else EntryGrid(entries, open, long)

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
