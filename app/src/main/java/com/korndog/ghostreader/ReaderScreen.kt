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
import androidx.compose.ui.text.AnnotatedString
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
    if (!chapter.title.startsWith("Section ")) return chapter.title
    val opening = chapter.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(48)
    return if (opening.isNullOrBlank()) chapter.title else opening
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

    val theme = readerThemes[themeIndex]
    val textStyle = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * spacing).sp,
        color = theme.text,
        fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        textAlign = TextAlign.Justify
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
            // Slim header: back, where you are, and the settings button.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹ Shelf",
                    color = theme.accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClose() }.padding(8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    displayTitle(chapters[chapterIndex]),
                    color = theme.text.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Aa",
                    color = theme.accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showControls = !showControls }.padding(8.dp)
                )
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val widthPx = with(density) { (maxWidth - (margin * 2f).dp).roundToPx() }.coerceAtLeast(100)
                val heightPx = with(density) { (maxHeight - 16.dp).roundToPx() }.coerceAtLeast(150)
                val text = chapters[chapterIndex].text

                // One layout of the whole chapter, then cut it into pages at line boundaries.
                val starts = remember(chapterIndex, widthPx, heightPx, fontSize, spacing, serif) {
                    val layout = measurer.measure(
                        AnnotatedString(text),
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
                val pageText = text.substring(starts[page].coerceAtMost(end), end).trim()

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
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(theme.text.copy(alpha = 0.15f))
                ) {
                    Box(Modifier.fillMaxWidth(bookProgress).fillMaxHeight().background(theme.accent))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Page $pageNumber of $pageCount  •  Chapter ${chapterIndex + 1} of ${chapters.size}",
                        color = theme.text.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(bookProgress * 100).toInt()}%",
                        color = theme.accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
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
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
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
            }
        }
    }
}
