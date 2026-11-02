package com.vibetavern.launcher.client.platform

import com.vibetavern.client.data.TextStore
import java.io.File
import java.io.IOException

/** [TextStore] on a file, written via a temp file + rename so a crash never leaves half a document. */
class FileTextStore(private val file: File) : TextStore {

    override fun read(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: IOException) {
        null
    }

    override fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // renameTo can refuse to replace an existing file on some filesystems.
            file.delete()
            check(tmp.renameTo(file)) { "Could not save ${file.name}" }
        }
    }
}
