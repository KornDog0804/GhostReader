package com.korndog.ghostreader

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ReaderTheme(val name: String, val bg: Color, val text: Color, val accent: Color)

private val readerThemes = listOf(
    ReaderTheme("Night", Color(0xFF0B0911), Color(0xFFF3EDF9), Color(0xFFB784FF)),
    ReaderTheme("AMOLED", Color(0xFF000000), Color(0xFFE6E6E6), Color(0xFFB784FF)),
    ReaderTheme("Sepia", Color(0xFFF4ECD8), Color(0xFF4A3B2C), Color(0xFF8A5A2B)),
    ReaderTheme("Paper", Color(0xFFFAFAF5), Color(0xFF1C1C1C), Color(0xFF6B3FD4))
)

/** Sections without a real heading are shown by their opening words instead of "Section 7". */
private fun displayTitle(chapter: Chapter): String {
    val generic = chapter.title.startsWith("Section ") || Regex("^(Chapter|Part)\\s*\\d+$", RegexOption.IGNORE_CASE).matches(chapter.title.trim())
    if (!generic) return chapter.title
    // First real line that is not just a number or a very short label.
    val opening = chapter.text.lineSequence().map { it.trim() }
        .firstOrNull { it.length > 3 && !it.all { c -> c.isDigit() || c == '.' || c == ' ' } }
        ?.let { if (it.length > 44) it.take(44).trimEnd() + "…" else it }
    return when {
        opening.isNullOrBlank() -> chapter.title
        chapter.title.startsWith("Section ") -> opening
        else -> chapter.title + "  ·  " + opening
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, theme: ReaderTheme, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) theme.bg else theme.text,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) theme.accent else theme.text.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * The reading screen: pages are cut from one layout of the whole chapter (fast), fit the real
 * space on screen, and your place is saved as a character position so it survives font changes.
 */
@Composable
internal fun ReaderScreen(
    title: String,
    author: String,
    chapters: List<Chapter>,
    prefs: SharedPreferences,
    bookKey: String,
    onClose: () -> Unit
) {
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("r_theme", 0).coerceIn(0, readerThemes.lastIndex)) }
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("fontSize", 18f).coerceIn(14f, 32f)) }
    var spacing by remember { mutableFloatStateOf(prefs.getFloat("r_spacing", 1.5f).coerceIn(1.2f, 2.0f)) }
    var serif by remember { mutableStateOf(prefs.getBoolean("r_serif", true)) }
    var margin by remember { mutableFloatStateOf(prefs.getInt("r_margin", 22).coerceIn(12, 40).toFloat()) }
    var chapterIndex by remember { mutableIntStateOf(prefs.getInt(bookKey, 0).coerceIn(0, chapters.lastIndex)) }
    var offset by remember { mutableIntStateOf(prefs.getInt(bookKey + "_off", 0).coerceAtLeast(0)) }
    var showControls by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var pageNumber by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(1) }
    var pageEnd by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var tocTab by remember { mutableIntStateOf(0) }
    var bookmarks by remember {
        mutableStateOf(
            prefs.getString(bookKey + "_bm", "").orEmpty().split(';').mapNotNull { item ->
                val parts = item.split(':')
                val c = parts.getOrNull(0)?.toIntOrNull()
                val o = parts.getOrNull(1)?.toIntOrNull()
                if (c != null && o != null && c in chapters.indices) c to o else null
            }
        )
    }
    fun saveBookmarks(list: List<Pair<Int, Int>>) {
        bookmarks = list
        prefs.edit().putString(bookKey + "_bm", list.joinToString(";") { "${it.first}:${it.second}" }).apply()
    }

    val theme = readerThemes[themeIndex]
    val textStyle = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * spacing).sp,
        color = theme.text,
        fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        textAlign = TextAlign.Start
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val totalChars = remember(chapters) { chapters.sumOf { it.text.length }.coerceAtLeast(1) }
    val charsBefore = remember(chapters) {
        var running = 0
        chapters.map { val before = running; running += it.text.length; before }
    }

    BackHandler {
        when {
            showToc -> showToc = false
            showControls -> showControls = false
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Header: back, title and author, bookmark this page, more settings.
            val marked = bookmarks.any { it.first == chapterIndex && it.second >= offset && it.second < pageEnd }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(60.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←",
                    color = theme.accent,
                    fontSize = 24.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 14.dp, vertical = 10.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = theme.text,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (author.isNotBlank() && !author.startsWith("Unknown")) author else displayTitle(chapters[chapterIndex]),
                        color = theme.text.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (marked) "★" else "☆",
                    color = theme.accent,
                    fontSize = 22.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        if (marked) saveBookmarks(bookmarks.filterNot { it.first == chapterIndex && it.second >= offset && it.second < pageEnd })
                        else saveBookmarks(bookmarks + (chapterIndex to offset))
                    }.padding(horizontal = 12.dp, vertical = 10.dp)
                )
                Text(
                    "⋮",
                    color = theme.accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showControls = !showControls }.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val widthPx = with(density) { (maxWidth - (margin * 2f).dp).roundToPx() }.coerceAtLeast(100)
                val heightPx = with(density) { (maxHeight - 16.dp).roundToPx() }.coerceAtLeast(150)
                val chapter = chapters[chapterIndex]
                val text = chapter.text
                // The chapter text with its bold, italics and heading sizes.
                val annotated = remember(chapterIndex, fontSize) {
                    buildAnnotatedString {
                        append(chapter.text)
                        chapter.spans.forEach { span ->
                            addStyle(
                                SpanStyle(
                                    fontWeight = if (span.bold) FontWeight.Bold else null,
                                    fontStyle = if (span.italic) FontStyle.Italic else null,
                                    fontSize = if (span.scale != 1f) (fontSize * span.scale).sp else TextUnit.Unspecified
                                ),
                                span.start.coerceIn(0, chapter.text.length),
                                span.end.coerceIn(0, chapter.text.length)
                            )
                        }
                    }
                }

                // One layout of the whole chapter, then cut it into pages at line boundaries.
                val starts = remember(chapterIndex, widthPx, heightPx, fontSize, spacing, serif) {
                    val layout = measurer.measure(
                        annotated,
                        style = textStyle,
                        constraints = Constraints(maxWidth = widthPx)
                    )
                    val result = mutableListOf(0)
                    var pageTop = 0f
                    var firstLine = 0
                    for (line in 0 until layout.lineCount) {
                        if (line > firstLine && layout.getLineBottom(line) - pageTop > heightPx) {
                            result.add(layout.getLineStart(line))
                            pageTop = layout.getLineTop(line)
                            firstLine = line
                        }
                    }
                    result
                }
                val page = starts.indexOfLast { it <= offset }.coerceAtLeast(0)

                LaunchedEffect(chapterIndex, page, starts) {
                    val top = starts[page]
                    if (offset != top) offset = top
                    pageNumber = page + 1
                    pageCount = starts.size
                    pageEnd = starts.getOrElse(page + 1) { Int.MAX_VALUE }
                    prefs.edit().putInt(bookKey, chapterIndex).putInt(bookKey + "_off", top).apply()
                }

                fun next() {
                    if (page < starts.lastIndex) offset = starts[page + 1]
                    else if (chapterIndex < chapters.lastIndex) { chapterIndex++; offset = 0 }
                }

                fun previous() {
                    if (page > 0) offset = starts[page - 1]
                    else if (chapterIndex > 0) { chapterIndex--; offset = Int.MAX_VALUE }
                }

                val end = starts.getOrElse(page + 1) { text.length }.coerceAtMost(text.length)
                var from = starts[page].coerceAtMost(end)
                var to = end
                while (from < to && text[from].isWhitespace()) from++
                while (to > from && text[to - 1].isWhitespace()) to--
                val pageText = annotated.subSequence(from, to)

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(page, chapterIndex, starts) {
                            detectTapGestures { position ->
                                val width = size.width
                                when {
                                    position.x < width * 0.3f -> previous()
                                    position.x > width * 0.7f -> next()
                                    else -> showControls = !showControls
                                }
                            }
                        }
                        .pointerInput(page, chapterIndex, starts) {
                            var drag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { drag = 0f },
                                onDragEnd = { if (drag > 80f) previous() else if (drag < -80f) next() }
                            ) { change, amount -> drag += amount; change.consume() }
                        }
                ) {
                    Text(
                        text = pageText,
                        style = textStyle,
                        modifier = Modifier.padding(horizontal = margin.dp, vertical = 8.dp)
                    )
                }
            }

            // Footer: thin progress bar for the whole book, then where you are.
            val inBook = (charsBefore[chapterIndex] + offset.coerceIn(0, chapters[chapterIndex].text.length)).toFloat()
            val bookProgress = (inBook / totalChars).coerceIn(0f, 1f)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                var dragValue by remember { mutableStateOf<Float?>(null) }
                androidx.compose.material3.Slider(
                    value = dragValue ?: bookProgress,
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { fraction ->
                            val target = (fraction * totalChars).toInt().coerceIn(0, totalChars - 1)
                            val ci = charsBefore.indexOfLast { it <= target }.coerceAtLeast(0)
                            chapterIndex = ci
                            offset = (target - charsBefore[ci]).coerceAtLeast(0)
                        }
                        dragValue = null
                    },
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = theme.accent,
                        activeTrackColor = theme.accent,
                        inactiveTrackColor = theme.text.copy(alpha = 0.18f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Page $pageNumber of $pageCount  •  Chapter ${chapterIndex + 1} of ${chapters.size}",
                        color = theme.text.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (bookProgress < 0.1f) "%.1f%%".format(bookProgress * 100) else "${(bookProgress * 100).toInt()}%",
                        color = theme.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Bottom row: contents, text settings, theme, bookmarks.
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                @Composable
                fun Tool(glyph: String, onClick: () -> Unit) {
                    Text(
                        glyph,
                        color = theme.accent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }
                Tool("☰") { tocTab = 0; showToc = true }
                Tool("Aa") { showControls = !showControls }
                Tool("◐") {
                    themeIndex = (themeIndex + 1) % readerThemes.size
                    prefs.edit().putInt("r_theme", themeIndex).apply()
                }
                Tool("★") { tocTab = 1; showToc = true }
            }
        }

        if (showControls) {
            val panel = lerp(theme.bg, theme.text, 0.1f)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(panel)
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Text("Theme", color = theme.text.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    readerThemes.forEachIndexed { index, t ->
                        Pill(t.name, index == themeIndex, theme) {
                            themeIndex = index
                            prefs.edit().putInt("r_theme", index).apply()
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val sliderColors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent)
                Text("Text size  ${fontSize.toInt()}", color = theme.text.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = { prefs.edit().putFloat("fontSize", fontSize).apply() },
                    valueRange = 14f..32f,
                    colors = sliderColors
                )
                Text("Line spacing", color = theme.text.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = spacing,
                    onValueChange = { spacing = it },
                    onValueChangeFinished = { prefs.edit().putFloat("r_spacing", spacing).apply() },
                    valueRange = 1.2f..2.0f,
                    colors = sliderColors
                )
                Text("Margins", color = theme.text.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = margin,
                    onValueChange = { margin = it },
                    onValueChangeFinished = { prefs.edit().putInt("r_margin", margin.toInt()).apply() },
                    valueRange = 12f..40f,
                    colors = sliderColors
                )
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Pill("Serif", serif, theme) { serif = true; prefs.edit().putBoolean("r_serif", true).apply() }
                    Pill("Sans", !serif, theme) { serif = false; prefs.edit().putBoolean("r_serif", false).apply() }
                    Pill("Contents ☰", false, theme) { showControls = false; showToc = true }
                    Pill("Done", false, theme) { showControls = false }
                }
            }
        }

        if (showToc) {
            Column(
                Modifier.fillMaxSize().background(theme.bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = theme.accent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Pill("Close", false, theme) { showToc = false }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Pill("Contents", tocTab == 0, theme) { tocTab = 0 }
                    Pill("Bookmarks (${bookmarks.size})", tocTab == 1, theme) { tocTab = 1 }
                }
                Spacer(Modifier.height(8.dp))
                if (tocTab == 0) {
                    val tocState = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = (chapterIndex - 2).coerceAtLeast(0)
                    )
                    LazyColumn(Modifier.fillMaxSize(), state = tocState) {
                        itemsIndexed(chapters) { index, chapter ->
                            Text(
                                "${index + 1}.  ${displayTitle(chapter)}",
                                color = if (index == chapterIndex) theme.accent else theme.text,
                                fontWeight = if (index == chapterIndex) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chapterIndex = index; offset = 0; showToc = false }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                } else if (bookmarks.isEmpty()) {
                    Text(
                        "No bookmarks yet. Tap the star at the top of a page to save your place.",
                        color = theme.text.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(bookmarks.sortedWith(compareBy({ it.first }, { it.second }))) { _, mark ->
                            val chapter = chapters[mark.first]
                            val percent = (mark.second * 100 / chapter.text.length.coerceAtLeast(1)).coerceIn(0, 100)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { chapterIndex = mark.first; offset = mark.second; showToc = false }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Chapter ${mark.first + 1}  •  ${displayTitle(chapter)}",
                                        color = theme.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("$percent% through the chapter", color = theme.text.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    "✕",
                                    color = theme.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { saveBookmarks(bookmarks.filterNot { it == mark }) }
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
