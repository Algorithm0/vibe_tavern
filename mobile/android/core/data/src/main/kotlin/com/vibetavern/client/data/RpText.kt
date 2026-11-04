package com.vibetavern.client.data

/** A run of message text with one set of styles. Markers (`*`, `**`) are removed; quote marks are kept. */
data class RpSegment(
    val text: String,
    val italic: Boolean = false,
    val bold: Boolean = false,
    val quoted: Boolean = false,
)

/**
 * Roleplay text formatting: `*actions*` in italics, `**strong**`, and "speech" highlighted.
 *
 * Deliberately forgiving: a marker with no closing partner in the same paragraph is plain
 * text, so a stray `*` or an apostrophe-style quote never swallows the rest of a message.
 * Pure Kotlin so it is unit tested; the UI maps segments to styled text.
 */
object RpText {

    fun parse(text: String): List<RpSegment> {
        val out = ArrayList<RpSegment>()
        val buf = StringBuilder()
        var italic = false
        var bold = false
        var quoted = false
        var i = 0

        fun flush() {
            if (buf.isNotEmpty()) {
                val seg = RpSegment(buf.toString(), italic, bold, quoted)
                val last = out.lastOrNull()
                if (last != null && last.copy(text = "") == seg.copy(text = "")) out[out.lastIndex] = last.copy(text = last.text + seg.text) else out += seg
                buf.setLength(0)
            }
        }

        /** True when [marker] occurs again before the paragraph ends. */
        fun hasCloser(from: Int, marker: String): Boolean {
            val end = text.indexOf("\n\n", from).let { if (it < 0) text.length else it }
            val at = text.indexOf(marker, from)
            return at in from until end
        }

        while (i < text.length) {
            val c = text[i]
            when {
                c == '*' && text.startsWith("**", i) -> {
                    if (bold || hasCloser(i + 2, "**")) { flush(); bold = !bold; i += 2 } else { buf.append("**"); i += 2 }
                }
                c == '*' -> {
                    if (italic || hasCloser(i + 1, "*")) { flush(); italic = !italic; i += 1 } else { buf.append(c); i += 1 }
                }
                c == '"' -> {
                    if (quoted) { buf.append(c); flush(); quoted = false; i += 1 }
                    else if (hasCloser(i + 1, "\"")) { flush(); quoted = true; buf.append(c); i += 1 }
                    else { buf.append(c); i += 1 }
                }
                c == '“' -> {
                    if (!quoted && hasCloser(i + 1, "”")) { flush(); quoted = true }
                    buf.append(c); i += 1
                }
                c == '”' -> {
                    buf.append(c)
                    if (quoted) { flush(); quoted = false }
                    i += 1
                }
                else -> { buf.append(c); i += 1 }
            }
        }
        flush()
        return out
    }
}
