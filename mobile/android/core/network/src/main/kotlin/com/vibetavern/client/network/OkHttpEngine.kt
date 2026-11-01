package com.vibetavern.client.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The app's [HttpEngine], on OkHttp.
 *
 * Cancellation is the reason for the watcher below. A person pressing "Stop" while the
 * model is silently thinking has to close the socket immediately, because that is what
 * tells the server to abort the generation. A blocking read cannot observe coroutine
 * cancellation, but [Call.cancel] is thread-safe and unblocks it at once.
 *
 * The shared [client] is only a template: every call derives its own client so the
 * per-request read timeout (short for JSON, long for SSE) does not leak between calls.
 */
class OkHttpEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build(),
) : HttpEngine {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        coroutineScope {
            val call = newCall(request)
            val watcher = cancelCallOnCancel(call)
            try {
                call.execute().use { response ->
                    HttpResponse(
                        status = response.code,
                        headers = response.headers.toMultimap(),
                        body = response.body?.bytes() ?: ByteArray(0),
                    )
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // cancelled: surface cancellation, not a network error
                throw ApiException.network(e)
            } finally {
                watcher.cancel()
            }
        }
    }

    override fun streamLines(request: HttpRequest): Flow<String> = channelFlow {
        withContext(Dispatchers.IO) {
            val call = newCall(request)
            val watcher = cancelCallOnCancel(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ApiErrorParser.parse(response.code, response.body?.string().orEmpty())
                    }
                    val source = response.body?.source() ?: return@use
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        send(line)
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ApiException.network(e)
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun CoroutineScope.cancelCallOnCancel(call: Call) = launch(Dispatchers.Default) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }

    private fun newCall(request: HttpRequest): Call {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
        // OkHttp insists on a body for POST/PUT/PATCH even when empty.
        val effectiveBody = body ?: if (request.method in BODY_REQUIRED) ByteArray(0).toRequestBody(null) else null
        builder.method(request.method, effectiveBody)

        val perCall = client.newBuilder()
            .readTimeout(request.readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
        return perCall.newCall(builder.build())
    }

    private companion object {
        val BODY_REQUIRED = setOf("POST", "PUT", "PATCH")
    }
}
