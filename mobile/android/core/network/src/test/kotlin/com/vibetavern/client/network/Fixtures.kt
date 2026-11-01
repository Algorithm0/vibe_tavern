package com.vibetavern.client.network

/** Loads files from mobile/android/fixtures (real captures from a running server). */
internal object Fixtures {
    fun text(name: String): String {
        val stream = Fixtures::class.java.classLoader.getResourceAsStream(name)
            ?: error("Missing fixture $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
