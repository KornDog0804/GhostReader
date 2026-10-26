package com.korndog.ghostreader

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** A stretch of a chapter that is bold, italic, or a different size (headings). */
internal data class TextSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val scale: Float = 1f
)

internal data class CleanText(val text: String, val spans: List<TextSpan>)

/**
 * Turns the text Android builds from a chapter's HTML into clean reading text.
 *
 * - drops the object placeholders left where images were,
 * - tidies spaces around line breaks and caps blank lines at one,
 * - keeps bold, italic and heading sizes, moved to their new positions.
 */
internal fun cleanSpanned(source: Spanned): CleanText {
    val s = source.toString()
    val out = StringBuilder(s.length)
    val map = IntArray(s.length + 1)

    fun isBlank(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '￼'

    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (isBlank(c)) {
            var j = i
            var newlines = 0
            while (j < s.length && isBlank(s[j])) {
                if (s[j] == '\n') newlines++
                j++
            }
            for (k in i until j) map[k] = out.length
            when {
                newlines >= 2 -> out.append("\n\n")
                newlines == 1 -> out.append('\n')
                out.isNotEmpty() -> out.append(' ')
            }
            i = j
        } else {
            map[i] = out.length
            out.append(c)
            i++
        }
    }
    map[s.length] = out.length

    val raw = out.toString()
    val lead = raw.length - raw.trimStart().length
    val text = raw.trim()

    fun mapped(index: Int): Int = (map[index.coerceIn(0, s.length)] - lead).coerceIn(0, text.length)

    val spans = mutableListOf<TextSpan>()
    for (span in source.getSpans(0, s.length, Any::class.java)) {
        val start = mapped(source.getSpanStart(span))
        val end = mapped(source.getSpanEnd(span))
        if (end <= start) continue
        when (span) {
            is StyleSpan -> {
                val bold = span.style == Typeface.BOLD || span.style == Typeface.BOLD_ITALIC
                val italic = span.style == Typeface.ITALIC || span.style == Typeface.BOLD_ITALIC
                if (bold || italic) spans.add(TextSpan(start, end, bold = bold, italic = italic))
            }
            is RelativeSizeSpan -> if (span.sizeChange != 1f) spans.add(TextSpan(start, end, scale = span.sizeChange))
        }
    }
    return CleanText(text, spans)
}
