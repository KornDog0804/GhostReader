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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
    val lastRead: Long = 0L,
    val added: Long = 0L
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

/** What the home screen offers to resume. */
internal data class HomeContinue(
    val title: String,
    val progress: Float,
    val isAudio: Boolean,
    val cover: Bitmap?,
    val book: Book?,
    val audio: File?
)

/** A tappable settings/download row: glyph, title, one line of help. */
@Composable
internal fun ActionCard(glyph: String, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF17111F))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A1D42)),
            contentAlignment = Alignment.Center
        ) { Text(glyph, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Color(0xFF7C7090), fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF9B8DB0), fontSize = 12.sp, lineHeight = 15.sp)
        }
        Text("›", color = Color(0xFF9B8DB0), fontSize = 20.sp)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(bottom = 8.dp))
}

/** Soft purple wash behind the listening screen. */
@Composable
internal fun AudioBookPlayerFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF211338), Color(0xFF0B0911), Color(0xFF0B0911))))) {
        content()
    }
}

// ---------------------------------------------------------------- home

/** The front door: the GhostReader art with My Library and Add Books. */
@Composable
internal fun HomeSplash(onLibrary: () -> Unit, onAddBooks: () -> Unit, resume: HomeContinue? = null, onResume: (HomeContinue) -> Unit = {}) {
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
        if (resume != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF2A1D42), Color(0xFF14101C))))
                    .clickable { onResume(resume) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (resume.cover != null) {
                    androidx.compose.foundation.Image(
                        bitmap = resume.cover.asImageBitmap(),
                        contentDescription = null,
                        contentScale = if (resume.isAudio) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.width(46.dp).height(if (resume.isAudio) 46.dp else 66.dp).clip(RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (resume.isAudio) "CONTINUE LISTENING" else "CONTINUE READING", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(resume.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A2D52))) {
                        Box(Modifier.fillMaxWidth(resume.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(percentText(resume.progress), color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color(0x00B784FF), Color(0x66B784FF), Color(0x00B784FF)))))
    NavigationBar(containerColor = Color(0xFF0C0813), modifier = Modifier.navigationBarsPadding()) {
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

/** Poster art: ebooks fill the poster; square audiobook art sits on a blurred copy of itself. */
@Composable
private fun PosterArt(entry: ShelfEntry, modifier: Modifier) {
    val cover = entry.cover
    Box(modifier) {
        if (cover != null && entry.audio != null) {
            Image(
                bitmap = cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp)
            )
            Box(Modifier.fillMaxSize().background(Color(0x73000000)))
            Image(
                bitmap = cover.asImageBitmap(), contentDescription = "Cover of ${entry.title}", contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(1f)
            )
        } else {
            ShelfCover(entry.cover, entry.title, entry.author, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun BoxScope.PosterBadges(entry: ShelfEntry) {
    if (entry.audio != null) {
        Text(
            "🎧",
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .padding(bottom = if (entry.inProgress) 5.dp else 0.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
    when {
        entry.finished -> Text(
            "✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF6D3FD0)).padding(horizontal = 6.dp, vertical = 2.dp)
        )
        !entry.started -> Text(
            "NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF6D3FD0)).padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    if (entry.inProgress) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color(0x99000000))
        ) {
            Box(Modifier.fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent))
        }
    }
}

/** A poster with a glass finish: soft sheen across the art and a thin lit edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassPoster(entry: ShelfEntry, modifier: Modifier, showTitle: Boolean, onOpen: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).combinedClickable(onClick = onOpen, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)) {
            PosterArt(entry, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0x40FFFFFF), Color(0x00FFFFFF), Color(0x00FFFFFF), Color(0x1FFFFFFF)))
                )
            )
            Box(
                Modifier.fillMaxSize().border(
                    1.dp, Brush.linearGradient(listOf(Color(0x99FFFFFF), Color(0x14FFFFFF), Color(0x66FFFFFF))), shape
                )
            )
            PosterBadges(entry)
        }
        if (showTitle) {
            Spacer(Modifier.height(7.dp))
            Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.author.isNotBlank() && !entry.author.startsWith("Unknown")) {
                Text(entry.author, color = Color(0xFF9B8DB0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun CoverTile(entry: ShelfEntry, onOpen: () -> Unit, onLong: () -> Unit) =
    GlassPoster(entry, Modifier, true, onOpen, onLong)

/** The big banner: a blurred wash of the cover, the poster, title and one clear button. */
@Composable
private fun Hero(entry: ShelfEntry, onOpen: () -> Unit, onLong: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        val cover = entry.cover
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(44.dp).alpha(0.6f)
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A1F6B), screenBg))))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x66000000), screenBg))))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.width(158.dp)) { GlassPoster(entry, Modifier, false, onOpen, onLong) }
            Spacer(Modifier.height(14.dp))
            Text(
                entry.title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp, lineHeight = 24.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (entry.author.isNotBlank() && !entry.author.startsWith("Unknown")) {
                Text(entry.author, color = Color(0xFFCDBFE3), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            if (entry.inProgress) {
                Box(Modifier.width(200.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(accent))
                }
                Spacer(Modifier.height(12.dp))
            }
            val label = when {
                entry.audio != null && entry.started -> "▶  Continue listening"
                entry.audio != null -> "▶  Start listening"
                entry.started -> "Continue reading"
                else -> "Start reading"
            }
            Text(
                label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF7B4BE0), Color(0xFF5B34B8))))
                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ShelfRow(title: String, entries: List<ShelfEntry>, onOpen: (ShelfEntry) -> Unit, onLong: (ShelfEntry) -> Unit) {
    Column {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entries, key = { it.key + it.title }) { e ->
                GlassPoster(e, Modifier.width(118.dp), true, { onOpen(e) }, { onLong(e) })
            }
        }
    }
}

/** The front page: hero banner, then shelves, like a streaming app. */
@Composable
private fun HomeTab(all: List<ShelfEntry>, onOpen: (ShelfEntry) -> Unit, onLong: (ShelfEntry) -> Unit) {
    val hero = all.filter { it.inProgress }.maxByOrNull { it.lastRead } ?: all.maxByOrNull { it.added }
    if (hero == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Your library is waiting. Open Downloads to add books.", color = Color(0xFF9B8DB0), textAlign = TextAlign.Center)
        }
        return
    }
    val continuing = all.filter { it.inProgress }.sortedByDescending { it.lastRead }
    val audiobooks = all.filter { it.audio != null }.sortedByDescending { it.added }
    val ebooks = all.filter { it.audio == null }.sortedByDescending { it.added }
    val recent = all.sortedByDescending { it.added }.take(12)
    val finished = all.filter { it.finished }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { Hero(hero, onOpen = { onOpen(hero) }, onLong = { onLong(hero) }) }
        if (continuing.isNotEmpty()) item { ShelfRow("Continue", continuing, onOpen, onLong) }
        if (audiobooks.isNotEmpty()) item { ShelfRow("Audiobooks", audiobooks, onOpen, onLong) }
        if (ebooks.isNotEmpty()) item { ShelfRow("Books", ebooks, onOpen, onLong) }
        if (all.size > 3) item { ShelfRow("Recently added", recent, onOpen, onLong) }
        if (finished.isNotEmpty()) item { ShelfRow("Finished", finished, onOpen, onLong) }
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
                    lastRead = prefs.getLong(key + "_ts", 0L), added = book.file.lastModified()
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
                    lastRead = prefs.getLong("ats_$key", 0L), added = file.lastModified()
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
                    Text("$b ${if (b == 1) "book" else "books"}  ·  $a ${if (a == 1) "audiobook" else "audiobooks"}", color = Color(0xFF9B8DB0), fontSize = 12.sp)
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
            listOf("Home", "All", "Authors", "Series").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, fontWeight = FontWeight.SemiBold) })
            }
        }
        // Filter chips and sort (All tab).
        if (tab == 1) androidx.compose.foundation.lazy.LazyRow(
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
                tab == 0 -> HomeTab(all, open, long)

                tab == 1 -> if (entries.isEmpty()) {
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
                } else EntryGrid(entries, open, long)

                tab == 2 -> GroupList(
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
