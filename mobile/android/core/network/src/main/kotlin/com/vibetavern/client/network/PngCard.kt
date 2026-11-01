package com.vibetavern.client.network

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/** What a character-card PNG carries, ready for `POST /api/import/json`. */
data class PngCardPayload(
    /** SillyTavern card JSON (`ccv3` preferred over `chara`). */
    val jsonText: String?,
    /** Lossless native Vibe Tavern monolith (`vtmd` chunk). The server prefers it over [jsonText]. */
    val monolithText: String?,
) {
    val isEmpty: Boolean get() = jsonText == null && monolithText == null
}

/**
 * Reads character-card metadata out of a PNG, mirroring the web app's
 * `png-reader.ts`: `tEXt` and `iTXt` chunks, base64-or-raw JSON, V3 over V2.
 */
object PngCard {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    class InvalidPngException(message: String) : Exception(message)

    data class TextChunk(val keyword: String, val text: String)

    fun extract(png: ByteArray): PngCardPayload {
        val chunks = readTextChunks(png)
        val monolith = chunks.firstOrNull { it.keyword == "vtmd" }?.let { decodeBase64Utf8(it.text) }
        val json = sequenceOf("ccv3", "chara")
            .mapNotNull { key -> chunks.firstOrNull { it.keyword == key } }
            .mapNotNull { decodeCardJson(it.text) }
            .firstOrNull()
        return PngCardPayload(jsonText = json, monolithText = monolith)
    }

    fun readTextChunks(png: ByteArray): List<TextChunk> {
        if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            throw InvalidPngException("Not a valid PNG file")
        }
        val result = mutableListOf<TextChunk>()
        var offset = 8
        while (offset + 8 <= png.size) {
            val length = readInt(png, offset)
            if (length < 0) break
            val type = String(png, offset + 4, 4, Charsets.ISO_8859_1)
            val dataStart = offset + 8
            val dataEnd = dataStart.toLong() + length
            if (dataEnd > png.size) break // malformed chunk: stop, keep what we have

            val end = dataEnd.toInt()
            when (type) {
                "tEXt" -> parseText(png, dataStart, end)?.let(result::add)
                "iTXt" -> parseInternationalText(png, dataStart, end)?.let(result::add)
                "IEND" -> return result
            }
            offset = end + 4 // skip CRC
        }
        return result
    }

    private fun parseText(data: ByteArray, start: Int, end: Int): TextChunk? {
        val nul = indexOfZero(data, start, end)
        if (nul < 0) return null
        val keyword = String(data, start, nul - start, Charsets.ISO_8859_1)
        val text = String(data, nul + 1, end - nul - 1, Charsets.UTF_8)
        return TextChunk(keyword, text)
    }

    private fun parseInternationalText(data: ByteArray, start: Int, end: Int): TextChunk? {
        val nul = indexOfZero(data, start, end)
        if (nul < 0 || nul + 2 >= end) return null
        val keyword = String(data, start, nul - start, Charsets.ISO_8859_1)
        val compressed = data[nul + 1].toInt() == 1

        var pos = nul + 3 // skip compression flag and method
        pos = indexOfZero(data, pos, end).let { if (it < 0) return null else it + 1 } // language tag
        pos = indexOfZero(data, pos, end).let { if (it < 0) return null else it + 1 } // translated keyword
        if (pos > end) return null

        val raw = data.copyOfRange(pos, end)
        val text = if (compressed) inflate(raw)?.toString(Charsets.UTF_8) ?: return null else raw.toString(Charsets.UTF_8)
        return TextChunk(keyword, text)
    }

    /** Card text is base64 JSON in practice; some tools write raw JSON, so try both. */
    private fun decodeCardJson(text: String): String? {
        decodeBase64Utf8(text)?.let { decoded -> if (decoded.trimStart().startsWith("{")) return decoded }
        return text.takeIf { it.trimStart().startsWith("{") }
    }

    private fun decodeBase64Utf8(text: String): String? = try {
        Base64.getMimeDecoder().decode(text.trim()).toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun inflate(input: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun indexOfZero(data: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (data[i].toInt() == 0) return i
        return -1
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}
