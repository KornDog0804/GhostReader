package com.korndog.ghostreader

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Where one audiobook was left: milliseconds into the book, length in ms, and when it was saved (ms since 1970). */
internal data class AudioSpot(val positionMs: Long, val durationMs: Long, val ts: Long)

/**
 * Keeps each audiobook at the same spot on every device, using the same cloud file and format as the
 * GhostReader website and the TV app: state.json key "a|<book hash>" = {t seconds, d seconds, ts ms}.
 * The newest timestamp wins. Only "a|" keys for books this phone has are ever touched.
 */
internal object AudioProgress {
    private const val MIN_STAMP_MOVE_MS = 2000L

    fun key(hash: String) = "a|$hash"

    /**
     * Pure merge. [local] maps book hash to this phone's spot. Returns the updated state (all other keys left as they were),
     * the spots to pull onto this phone, and whether the cloud file needs writing.
     */
    fun merge(state: JSONObject, local: Map<String, AudioSpot>): Merge {
        val pulls = HashMap<String, AudioSpot>()
        var changed = false
        for ((hash, mine) in local) {
            if (state.has("x|$hash")) continue // deleted everywhere: never bring it back
            val theirs = state.optJSONObject(key(hash))
            val theirTs = theirs?.optLong("ts", 0L) ?: 0L
            when {
                mine.ts > theirTs -> {
                    val d = if (mine.durationMs > 0) mine.durationMs / 1000.0 else theirs?.optDouble("d", 0.0) ?: 0.0
                    state.put(key(hash), JSONObject().put("t", mine.positionMs / 1000.0).put("d", d).put("ts", mine.ts))
                    changed = true
                }
                theirs != null && theirTs > mine.ts && theirs.has("t") ->
                    pulls[hash] = AudioSpot((theirs.getDouble("t") * 1000).toLong().coerceAtLeast(0), (theirs.optDouble("d", 0.0) * 1000).toLong(), theirTs)
            }
        }
        return Merge(state, pulls, changed)
    }

    class Merge(val state: JSONObject, val pulls: Map<String, AudioSpot>, val changed: Boolean)

    /**
     * Records this phone's position for a file. The spot only gets a new "saved at" time when it really moved,
     * so just having the app open never overrides a newer spot from the TV. A jump back to the very start from far
     * into the book is stored locally but not stamped, so a player that starts before it has seeked cannot erase your place.
     */
    fun record(context: Context, filename: String, positionMs: Long) {
        if (filename.isBlank()) return
        val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
        val old = prefs.getInt("audio_$filename", 0).toLong()
        val pos = positionMs.coerceIn(0, Int.MAX_VALUE.toLong())
        val e = prefs.edit().putInt("audio_$filename", pos.toInt())
        val suspicious = pos < 3000 && old > 10_000
        if (!suspicious && Math.abs(pos - old) >= MIN_STAMP_MOVE_MS) e.putLong("audiots_$filename", System.currentTimeMillis())
        e.apply()
    }

    fun localSpot(context: Context, file: File): AudioSpot {
        val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
        return AudioSpot(prefs.getInt("audio_" + file.name, 0).toLong(), 0L, prefs.getLong("audiots_" + file.name, 0L))
    }

    fun applyPull(context: Context, file: File, spot: AudioSpot) {
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).edit()
            .putInt("audio_" + file.name, spot.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .putLong("audiots_" + file.name, spot.ts).apply()
    }
}
