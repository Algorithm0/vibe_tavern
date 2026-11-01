package com.vibetavern.client.network

import java.io.ByteArrayOutputStream
import java.util.UUID

/** A fully built `multipart/form-data` body. */
class MultipartBody(val boundary: String, val bytes: ByteArray) {
    val contentType: String get() = "multipart/form-data; boundary=$boundary"
}

/** Tiny multipart/form-data writer, enough for file uploads. */
class MultipartBuilder(private val boundary: String = "----VibeTavern${UUID.randomUUID().toString().replace("-", "")}") {
    private val out = ByteArrayOutputStream()

    fun field(name: String, value: String): MultipartBuilder {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"${escape(name)}\"\r\n\r\n".toByteArray())
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray())
        return this
    }

    fun file(name: String, fileName: String, mimeType: String, bytes: ByteArray): MultipartBuilder {
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"${escape(name)}\"; filename=\"${escape(fileName)}\"\r\n"
                .toByteArray(Charsets.UTF_8),
        )
        out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
        return this
    }

    fun build(): MultipartBody {
        val closing = ByteArrayOutputStream()
        closing.write(out.toByteArray())
        closing.write("--$boundary--\r\n".toByteArray())
        return MultipartBody(boundary, closing.toByteArray())
    }

    private fun escape(value: String): String =
        value.replace("\"", "%22").replace("\r", "").replace("\n", "")
}
