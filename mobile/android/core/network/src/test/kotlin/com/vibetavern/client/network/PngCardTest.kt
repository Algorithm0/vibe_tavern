package com.vibetavern.client.network

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PngCardTest {

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val body = type.toByteArray(Charsets.ISO_8859_1) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    private fun text(keyword: String, value: String) =
        chunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + 0 + value.toByteArray(Charsets.UTF_8))

    private fun itxt(keyword: String, value: String, compress: Boolean): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        val payload = if (compress) {
            val d = Deflater()
            d.setInput(raw); d.finish()
            val buf = ByteArray(raw.size + 64)
            val n = d.deflate(buf); d.end()
            buf.copyOf(n)
        } else raw
        val header = keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, if (compress) 1 else 0, 0, 0, 0)
        return chunk("iTXt", header + payload)
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("IHDR", ByteArray(13)))
        chunks.forEach(out::write)
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun readsBase64CharaChunk() {
        val card = """{"spec":"chara_card_v2","data":{"name":"Алиса"}}"""
        val payload = PngCard.extract(png(text("chara", b64(card))))
        assertEquals(card, payload.jsonText)
        assertNull(payload.monolithText)
    }

    @Test
    fun ccv3WinsOverChara() {
        val v2 = """{"spec":"chara_card_v2"}"""
        val v3 = """{"spec":"chara_card_v3"}"""
        val payload = PngCard.extract(png(text("chara", b64(v2)), text("ccv3", b64(v3))))
        assertEquals(v3, payload.jsonText)
    }

    @Test
    fun fallsBackToCharaWhenCcv3Undecodable() {
        val v2 = """{"spec":"chara_card_v2"}"""
        val payload = PngCard.extract(png(text("ccv3", "%%%not base64 or json%%%"), text("chara", b64(v2))))
        assertEquals(v2, payload.jsonText)
    }

    @Test
    fun acceptsRawJsonChunk() {
        val raw = """{"name":"Raw"}"""
        assertEquals(raw, PngCard.extract(png(text("chara", raw))).jsonText)
    }

    @Test
    fun readsCompressedAndPlainItxt() {
        val card = """{"name":"Compressed ✓"}"""
        assertEquals(card, PngCard.extract(png(itxt("chara", b64(card), compress = true))).jsonText)
        assertEquals(card, PngCard.extract(png(itxt("ccv3", b64(card), compress = false))).jsonText)
    }

    @Test
    fun readsVtmdMonolith() {
        val md = "---\nname: Aria\n---\n# Description\nA fire mage.\n"
        val payload = PngCard.extract(png(text("vtmd", b64(md))))
        assertEquals(md, payload.monolithText)
        assertNull(payload.jsonText)
    }

    @Test
    fun cardWithoutMetadataIsEmpty() {
        assertTrue(PngCard.extract(png()).isEmpty)
    }

    @Test
    fun rejectsNonPng() {
        try {
            PngCard.extract("hello".toByteArray())
            error("expected failure")
        } catch (e: PngCard.InvalidPngException) {
            assertEquals("Not a valid PNG file", e.message)
        }
    }

    @Test
    fun truncatedChunkKeepsEarlierMetadata() {
        val card = """{"name":"Kept"}"""
        val full = png(text("chara", b64(card)), text("junk", "x".repeat(200)))
        val cut = full.copyOf(full.size - 150) // chops into the middle of the second chunk
        assertEquals(card, PngCard.extract(cut).jsonText)
    }

    @Test
    fun hugeBogusLengthDoesNotOverflowOrCrash() {
        val bogus = signature + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + "tEXt".toByteArray() + ByteArray(8)
        assertTrue(PngCard.extract(bogus).isEmpty)
    }

    @Test
    fun negativeLengthStops() {
        val bogus = signature + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + "tEXt".toByteArray() + ByteArray(8)
        assertTrue(PngCard.extract(bogus).isEmpty)
    }

    @Test
    fun realPngFromServerHasNoCardButParses() {
        // A small PNG with only IHDR/IDAT/IEND, like an avatar without embedded card data.
        val bytes = PngCardTest::class.java.classLoader.getResourceAsStream("a.png")?.use { it.readBytes() }
        if (bytes != null) assertTrue(PngCard.extract(bytes).isEmpty)
    }
}
