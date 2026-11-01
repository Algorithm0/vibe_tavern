package com.vibetavern.client.network

import java.net.URI
import java.net.URLDecoder

/** A server location plus the access token, when the input carried one. */
data class ParsedServerAddress(val baseUrl: String, val token: String?)

/**
 * Parses what a person types or scans into a normalised server address.
 *
 * Accepted inputs:
 *  - `192.168.1.5` → `http://192.168.1.5:8787` (the server's default port is assumed
 *    only when no scheme and no port were given)
 *  - `192.168.1.5:9000`, `my-host.local:8787`
 *  - `http://host`, `https://host.example.com` (explicit schemes keep their own default port)
 *  - the Mobile Access QR link `http://192.168.1.5:8787/#token=abc` (token in the
 *    fragment, as built by the web app) and `...?token=abc`
 *  - IPv6 literals such as `[fd00::5]:8787`
 */
object ServerAddress {

    const val DEFAULT_PORT = 8787
    const val LOCAL_BASE_URL = "http://127.0.0.1:$DEFAULT_PORT"

    fun parse(input: String): ParsedServerAddress? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

        val hasScheme = "://" in trimmed
        val withScheme = if (hasScheme) trimmed else "http://$trimmed"

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null

        val port = when {
            uri.port != -1 -> uri.port
            !hasScheme -> DEFAULT_PORT
            else -> -1
        }
        val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val path = uri.rawPath.orEmpty().trimEnd('/')

        val baseUrl = buildString {
            append(scheme).append("://").append(hostPart)
            if (port != -1) append(':').append(port)
            append(path)
        }
        val token = tokenFrom(uri.rawFragment) ?: tokenFrom(uri.rawQuery)
        return ParsedServerAddress(baseUrl, token)
    }

    private fun tokenFrom(component: String?): String? {
        if (component.isNullOrEmpty()) return null
        for (pair in component.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) != "token") continue
            val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
