package com.vibetavern.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {

    @Test
    fun buildsWellFormedBody() {
        val body = MultipartBuilder("BOUND")
            .field("note", "héllo")
            .file("crop", "a\"b.png", "image/png", byteArrayOf(1, 2, 3))
            .build()
        assertEquals("multipart/form-data; boundary=BOUND", body.contentType)
        val text = body.bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\n"))
        assertTrue(text.contains("filename=\"a%22b.png\""))
        assertTrue(text.contains("Content-Type: image/png\r\n\r\n\u0001\u0002\u0003\r\n"))
        assertTrue(text.endsWith("--BOUND--\r\n"))
    }

    @Test
    fun fieldValueIsUtf8() {
        val body = MultipartBuilder("B").field("t", "é").build()
        val bytes = body.bytes
        val needle = "é".toByteArray(Charsets.UTF_8)
        assertTrue((0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } })
    }

    @Test
    fun boundariesAreUniquePerBuilder() {
        assertTrue(MultipartBuilder().build().boundary != MultipartBuilder().build().boundary)
    }
}
