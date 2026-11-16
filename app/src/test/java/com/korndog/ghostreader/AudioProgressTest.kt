package com.korndog.ghostreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioProgressTest {
    private val h = "a".repeat(40)

    @Test fun newerPhoneSpotIsWrittenAndOtherKeysKept() {
        val st = JSONObject("""{"a|$h":{"t":300,"d":9000,"ts":100},"b|zz":{"p":1,"ts":5}}""")
        val m = AudioProgress.merge(st, mapOf(h to AudioSpot(315_000, 0, 200)))
        assertTrue(m.changed); assertTrue(m.pulls.isEmpty())
        assertEquals(315.0, m.state.getJSONObject("a|$h").getDouble("t"), 0.001)
        assertEquals(9000.0, m.state.getJSONObject("a|$h").getDouble("d"), 0.001) // old length kept when unknown
        assertTrue(m.state.has("b|zz"))
    }

    @Test fun newerCloudSpotIsPulledNotWritten() {
        val st = JSONObject("""{"a|$h":{"t":315,"d":9000,"ts":300}}""")
        val m = AudioProgress.merge(st, mapOf(h to AudioSpot(300_000, 0, 200)))
        assertFalse(m.changed)
        assertEquals(315_000L, m.pulls.getValue(h).positionMs)
        assertEquals(300L, m.pulls.getValue(h).ts)
    }

    @Test fun equalOrNeverPlayedChangesNothing() {
        val st = JSONObject("""{"a|$h":{"t":315,"d":9000,"ts":300}}""")
        assertFalse(AudioProgress.merge(st, mapOf(h to AudioSpot(1, 0, 300))).changed)
        val empty = AudioProgress.merge(JSONObject("{}"), mapOf(h to AudioSpot(0, 0, 0)))
        assertFalse(empty.changed); assertTrue(empty.pulls.isEmpty())
    }

    @Test fun firstEverSpotCreatesTheKey() {
        val m = AudioProgress.merge(JSONObject("{}"), mapOf(h to AudioSpot(5000, 60000, 10)))
        assertTrue(m.changed); assertEquals(60.0, m.state.getJSONObject("a|$h").getDouble("d"), 0.001)
    }

    @Test fun deletedEverywhereIsNeverRecreated() {
        val st = JSONObject("""{"x|$h":{"ts":1}}""")
        val m = AudioProgress.merge(st, mapOf(h to AudioSpot(5000, 0, 999)))
        assertFalse(m.changed); assertFalse(m.state.has("a|$h"))
    }
}
