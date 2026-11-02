package com.vibetavern.launcher.client.platform

import android.content.Context
import com.vibetavern.launcher.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Unpacks the bundled server payload (web UI, migrations, tokenizers, prompts) into
 * `filesDir/payload`, exactly as the original launcher screen does and with the same
 * "installed version" marker, so the two screens always agree on whether it is current.
 *
 * (The launcher screen keeps its own copy of this logic for now; folding it into this class
 * is a safe follow-up once the native client has been through a release.)
 */
object PayloadInstaller {

    const val PREFS = "vibe_tavern_launcher"
    const val PREF_PAYLOAD_VERSION = "installed_payload_version"
    private const val DIR = "payload"

    fun isCurrent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PAYLOAD_VERSION, null) == BuildConfig.VERSION_NAME &&
            hasRequiredPayload(File(context.filesDir, DIR))

    /** Blocking; call from an IO dispatcher. [onProgress] gets (copied, total). */
    fun ensureInstalled(context: Context, onProgress: (Int, Int) -> Unit) {
        if (isCurrent(context)) return
        val files = listAssetFiles(context, DIR)
        check(files.isNotEmpty()) { "Bundled server payload is missing" }
        val temporary = File(context.filesDir, "$DIR-copy-${UUID.randomUUID()}")
        val destination = File(context.filesDir, DIR)
        try {
            check(temporary.mkdirs()) { "Could not create payload staging directory" }
            files.forEachIndexed { index, relative ->
                val output = File(temporary, relative.removePrefix("$DIR/"))
                output.parentFile?.mkdirs()
                context.assets.open(relative).use { input -> FileOutputStream(output).use { input.copyTo(it) } }
                onProgress(index + 1, files.size)
            }
            check(hasRequiredPayload(temporary)) { "Bundled payload is incomplete" }
            if (destination.exists()) destination.deleteRecursively()
            check(temporary.renameTo(destination)) { "Could not activate extracted payload" }
        } catch (error: Exception) {
            temporary.deleteRecursively()
            throw error
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_PAYLOAD_VERSION, BuildConfig.VERSION_NAME).apply()
    }

    private fun hasRequiredPayload(payload: File): Boolean =
        File(payload, "web/index.html").isFile &&
            hasFiles(File(payload, "drizzle")) &&
            hasFiles(File(payload, "tokenizers")) &&
            hasFiles(File(payload, "prompts"))

    private fun hasFiles(directory: File): Boolean = directory.isDirectory && directory.walkTopDown().any { it.isFile }

    private fun listAssetFiles(context: Context, path: String): List<String> {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) return listOf(path)
        return children.flatMap { child -> listAssetFiles(context, "$path/$child") }
    }
}
