package com.korndog.ghostreader

import android.content.Context

/**
 * Read-only "how far along" numbers for the library tiles.
 *
 * Audiobooks already save their position (milliseconds) in the "reading" prefs, so the
 * shelf just divides by the file length. Ebooks, comics and PDFs only know their total
 * while open, so each reader also saves a plain 0..1 fraction here. This is a separate
 * file: it never touches, replaces or depends on the existing reading-position keys,
 * and losing it only means a tile shows no bar until the book is opened again.
 */
internal object ShelfProgress {
    private const val FILE = "shelf_progress"

    fun save(context: Context, key: String, fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        if (value.isNaN()) return
        runCatching {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            // Skip no-op writes: readers call this on every page turn.
            if (kotlin.math.abs(prefs.getFloat(key, -1f) - value) > 0.001f) prefs.edit().putFloat(key, value).apply()
        }
    }

    fun fraction(context: Context, entry: ShelfEntry): Float {
        val value = runCatching {
            when {
                entry.audio != null ->
                    if (entry.durationMs <= 0L) 0f
                    else context.getSharedPreferences("reading", Context.MODE_PRIVATE)
                        .getInt("audio_" + entry.audio.name, 0).toFloat() / entry.durationMs
                entry.book != null -> context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(entry.book.progressKey, 0f)
                entry.comic != null -> context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("comic:" + entry.comic.name, 0f)
                entry.pdf != null -> context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat("pdf:" + entry.pdf.name, 0f)
                else -> 0f
            }
        }.getOrDefault(0f)
        // Under 1% is noise (opened but not started); show nothing rather than a sliver.
        return if (value.isNaN() || value < 0.01f) 0f else value.coerceAtMost(1f)
    }
}
