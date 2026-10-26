package com.korndog.ghostreader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

    private fun folder(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

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

    /** Blocking. Call from a background thread. */
    fun fetch(context: Context, title: String, author: String): Bitmap? {
        val file = File(folder(context), key(title, author) + ".jpg")
        if (file.exists()) return runCatching { decodeBitmap(file.readBytes()) }.getOrNull()
        val miss = File(file.path + ".miss")
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS) return null
        return try {
            val query = "https://openlibrary.org/search.json?title=${URLEncoder.encode(title, "UTF-8")}" +
                (if (author.isNotBlank() && !author.startsWith("Unknown")) "&author=${URLEncoder.encode(author, "UTF-8")}" else "") +
                "&limit=5&fields=cover_i"
            val docs = JSONObject(String(get(query) ?: error("no result"))).getJSONArray("docs")
            var coverId = 0L
            for (i in 0 until docs.length()) {
                coverId = docs.getJSONObject(i).optLong("cover_i", 0L)
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
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.hsv(hue, 0.55f, 0.55f), Color.hsv((hue + 40f) % 360f, 0.7f, 0.25f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(6.dp)
        )
    }
}

/** The cover from the file itself, else one from Open Library, else a drawn one. Never blank. */
@Composable
internal fun ShelfCover(embedded: Bitmap?, title: String, author: String, modifier: Modifier) {
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
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        GeneratedCover(title, author, modifier)
    }
}
