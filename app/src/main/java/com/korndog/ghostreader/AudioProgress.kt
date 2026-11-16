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
    private const val MIN_STAMP_MOVE_MS = 500L // playing moves the place every second, so the saved-at time must follow small moves too

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
                // Same save time but a different place means this phone's copy drifted (an old build once zeroed it): trust the cloud.
                theirs != null && theirs.has("t") && (theirTs > mine.ts || (theirTs == mine.ts && Math.abs(theirs.getDouble("t") * 1000 - mine.positionMs) > 2000)) ->
                    pulls[hash] = AudioSpot((theirs.getDouble("t") * 1000).toLong().coerceAtLeast(0), (theirs.optDouble("d", 0.0) * 1000).toLong(), theirTs)
            }
        }
        return Merge(state, pulls, changed)
    }

    class Merge(val state: JSONObject, val pulls: Map<String, AudioSpot>, val changed: Boolean)

    /**
     * Records this phone's position for a file. The spot only gets a new "saved at" time when it really moved,
     * so just having the app open never overrides a newer spot from the TV. A jump back to the very start from far
     * into the book is ignored entirely, so a player that starts before it has seeked cannot erase your place
     * (to restart a book on purpose, seek to a few seconds in).
     */
    fun record(context: Context, filename: String, positionMs: Long) {
        if (filename.isBlank()) return
        val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
        val old = prefs.getInt("audio_$filename", 0).toLong()
        val pos = positionMs.coerceIn(0, Int.MAX_VALUE.toLong())
        // A player that has just been created reports 0:00 before it has jumped to your place. Never let that erase a real spot.
        if (pos < 3000 && old > 10_000) return
        val e = prefs.edit().putInt("audio_$filename", pos.toInt())
        if (Math.abs(pos - old) >= MIN_STAMP_MOVE_MS) e.putLong("audiots_$filename", System.currentTimeMillis())
        e.apply()
    }

    /** A spot saved by an older app version has a position but no "saved at" time. */
    fun isLegacy(spot: AudioSpot) = spot.ts == 0L && spot.positionMs > 0L

    /** Gives a legacy spot a time of now, so the place you were at on this phone is never overwritten by an older cloud value. */
    fun stampLegacy(context: Context, file: File) {
        val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
        if (prefs.getLong("audiots_" + file.name, 0L) == 0L && prefs.getInt("audio_" + file.name, 0) > 0)
            prefs.edit().putLong("audiots_" + file.name, System.currentTimeMillis()).apply()
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
