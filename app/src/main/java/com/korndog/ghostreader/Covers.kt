package com.korndog.ghostreader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Looks up a cover on Open Library (a free public catalogue) and keeps it on the phone. */
internal object CoverStore {
    private const val MISS_RETRY_MS = 3L * 24 * 60 * 60 * 1000

    private fun folder(context: Context) = File(context.filesDir, "covers2").apply { mkdirs() }

    private fun key(title: String, author: String) =
        (title.trim().lowercase() + "|" + author.trim().lowercase()).hashCode().toUInt().toString(16)

    private fun get(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "GhostReader/1.0")
        }
        return try {
            if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
        } finally {
            connection.disconnect()
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** A cover the reader picked for this item, if any. */
    fun overrideFile(context: Context, itemKey: String) = File(folder(context), "ov_" + itemKey.hashCode().toUInt().toString(16) + ".jpg")

    /** Blocking. Call from a background thread. */
    fun fetch(context: Context, title: String, author: String): Bitmap? {
        val file = File(folder(context), key(title, author) + ".jpg")
        if (file.exists()) return runCatching { decodeBitmap(file.readBytes()) }.getOrNull()
        val miss = File(file.path + ".miss")
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS) return null
        return try {
            val hasAuthor = author.isNotBlank() && !author.startsWith("Unknown")
            // Try the full title, then the part before a subtitle, then without a "Book 2" tail.
            val tries = listOf(
                title,
                title.substringBefore(':'),
                title.replace(Regex("(?i)[,:\\-–]?\\s*(?:[^:]*\\s)?(?:book|vol\\.?|volume)\\s*\\d+.*$"), ""),
                title.substringAfter(':', "").replace(Regex("(?i)\\s*(?:book|vol\\.?|volume)\\s*\\d+.*$"), "")
            ).map { it.trim().trim(',', '-', ':') }.filter { it.length >= 3 }.distinct()
            var coverId = 0L
            for (t in tries) {
                for (withAuthor in if (hasAuthor) listOf(true, false) else listOf(false)) {
                    val query = "https://openlibrary.org/search.json?title=${URLEncoder.encode(t, "UTF-8")}" +
                        (if (withAuthor) "&author=${URLEncoder.encode(author, "UTF-8")}" else "") +
                        "&limit=8&fields=cover_i,title,author_name"
                    val docs = runCatching { JSONObject(String(get(query) ?: error("no result"))).getJSONArray("docs") }.getOrNull() ?: continue
                    for (i in 0 until docs.length()) {
                        val doc = docs.getJSONObject(i)
                        val id = doc.optLong("cover_i", 0L)
                        if (id <= 0) continue
                        // Only accept a result whose title matches, and whose author matches when we know it.
                        val found = norm(doc.optString("title"))
                        val wanted = norm(t)
                        if (found.isEmpty() || !(found == wanted || found.startsWith(wanted) || wanted.startsWith(found))) continue
                        if (hasAuthor) {
                            val last = norm(author).split(' ').lastOrNull().orEmpty()
                            val names = doc.optJSONArray("author_name")
                            val ok = names != null && (0 until names.length()).any { norm(names.optString(it)).contains(last) }
                            if (last.isNotEmpty() && !ok) continue
                        }
                        coverId = id
                        break
                    }
                    if (coverId > 0) break
                }
                if (coverId > 0) break
            }
            if (coverId <= 0) error("no cover")
            val image = get("https://covers.openlibrary.org/b/id/$coverId-L.jpg?default=false") ?: error("no image")
            if (image.size < 4000) error("too small")
            file.writeBytes(image)
            decodeBitmap(image)
        } catch (_: Exception) {
            runCatching { miss.writeText("x") }
            null
        }
    }
}

/** A cover drawn from the title, so a shelf entry is never blank. */
@Composable
internal fun GeneratedCover(title: String, author: String, modifier: Modifier) {
    val hue = (abs(title.hashCode()) % 360).toFloat()
    // Long titles lose their subtitle / series tail so the main title fits whole.
    val main = title.substringBefore(':').ifBlank { title }.trim()
    val sub = title.substringAfter(':', "").replace(Regex("(?i)\\s*(?:book|vol\\.?|volume)\\s*\\d+.*$"), "").trim()
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.hsv(hue, 0.5f, 0.5f), Color.hsv((hue + 40f) % 360f, 0.7f, 0.2f))
                )
            )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(
                text = main,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = if (main.length > 28) 12.sp else 15.sp,
                lineHeight = if (main.length > 28) 14.sp else 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            if (author.isNotBlank() && !author.startsWith("Unknown")) {
                Text(author.uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The cover from the file itself, else one from Open Library, else a drawn one. Never blank. */
@Composable
internal fun ShelfCover(embedded: Bitmap?, title: String, author: String, modifier: Modifier, fit: Boolean = false) {
    val context = LocalContext.current
    var fetched by remember(title, author) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(title, author, embedded == null) {
        if (embedded == null) fetched = withContext(Dispatchers.IO) { runCatching { CoverStore.fetch(context, title, author) }.getOrNull() }
    }
    val bitmap = embedded ?: fetched
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Cover of $title",
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier
        )
    } else {
        GeneratedCover(title, author, modifier)
    }
}
