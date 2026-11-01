package com.vibetavern.client.network

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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

/**
 * Test-only [HttpEngine] on `java.net.HttpURLConnection`, so the transport contract can
 * run on a plain JVM without extra dependencies. The app uses [OkHttpEngine].
 *
 * Known limitation on the JDK: `disconnect()` blocks while another thread is parked in
 * a read of a chunked response, so cancelling a *silent* stream is not prompt here.
 * (On Android `HttpURLConnection` is OkHttp-backed, but the app does not rely on that.)
 */
class JdkUrlConnectionEngine(
    private val connectTimeoutMs: Int = 10_000,
) : HttpEngine {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        coroutineScope {
            val connection = open(request)
            val watcher = disconnectOnCancel(connection)
            try {
                write(connection, request)
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use(InputStream::readBytes) ?: ByteArray(0)
                HttpResponse(status, connection.headerFields.filterKeys { it != null }, body)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // cancelled: surface cancellation, not a network error
                throw ApiException.network(e)
            } finally {
                watcher.cancel()
                connection.disconnect()
            }
        }
    }

    override fun streamLines(request: HttpRequest): Flow<String> = channelFlow {
        withContext(Dispatchers.IO) {
            val connection = open(request)
            val watcher = disconnectOnCancel(connection)
            try {
                write(connection, request)
                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = connection.errorStream?.use(InputStream::readBytes)?.toString(Charsets.UTF_8).orEmpty()
                    throw ApiErrorParser.parse(status, body)
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        send(line)
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ApiException.network(e)
            } finally {
                watcher.cancel()
                connection.disconnect()
            }
        }
    }

    /** Disconnects as soon as the enclosing scope starts cancelling, even while a read is blocked. */
    private fun CoroutineScope.disconnectOnCancel(connection: HttpURLConnection) = launch(Dispatchers.Default) {
        try {
            awaitCancellation()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(request: HttpRequest): HttpURLConnection {
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw ApiException.network(e)
        }
        connection.requestMethod = request.method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = request.readTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
        return connection
    }

    private fun write(connection: HttpURLConnection, request: HttpRequest) {
        val body = request.body ?: return
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
    }
}
