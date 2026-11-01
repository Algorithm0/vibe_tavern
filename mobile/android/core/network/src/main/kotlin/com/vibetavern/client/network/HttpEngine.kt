package com.vibetavern.client.network

import kotlinx.coroutines.flow.Flow

/** A transport-agnostic HTTP request. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    /** Read timeout. Streaming calls use a much longer value because models can stall. */
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 60_000L
        const val STREAM_READ_TIMEOUT_MS = 5 * 60_000L
    }
}

class HttpResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val isSuccessful: Boolean get() = status in 200..299

    fun text(): String = body.toString(Charsets.UTF_8)

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * The only thing [VibeTavernApi] needs from the network stack.
 *
 * Production uses [OkHttpEngine]. Tests substitute fakes (or a JDK-based engine
 * against a live server), so the API logic never depends on Android.
 */
interface HttpEngine {

    /** Performs the request and buffers the whole body. Non-2xx responses are returned, not thrown. */
    suspend fun execute(request: HttpRequest): HttpResponse

    /**
     * Opens the request and emits the response body line by line (for SSE).
     * A non-2xx status throws [ApiException]; IO failures throw [ApiException.network].
     * Cancelling the collector aborts the underlying call.
     */
    fun streamLines(request: HttpRequest): Flow<String>
}
