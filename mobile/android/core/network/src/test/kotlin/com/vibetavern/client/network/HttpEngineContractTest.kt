package com.vibetavern.client.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test

/**
 * The transport contract every [HttpEngine] must satisfy, checked against an in-process
 * HTTP server. Subclasses supply the engine under test.
 */
abstract class HttpEngineContractTest {

    @get:Rule
    val timeout: Timeout = Timeout(15, TimeUnit.SECONDS)

    private lateinit var server: HttpServer
    protected abstract fun createEngine(): HttpEngine

    /** False where the engine cannot unblock a pending read on cancel (see JdkUrlConnectionEngine). */
    protected open val promptCancelWhileSilent: Boolean = true

    private val engine: HttpEngine by lazy { createEngine() }
    private val lastAuth = AtomicReference<String?>()
    private val lastBody = AtomicReference<String?>()
    private val clientGone = CountDownLatch(1)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { ex ->
            lastAuth.set(ex.requestHeaders.getFirst("Authorization"))
            lastBody.set(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val out = """{"ok":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.createContext("/fail") { ex ->
            val out = """{"error":{"kind":"Validation","message":"nope"}}""".toByteArray()
            ex.sendResponseHeaders(400, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.createContext("/empty") { ex ->
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.createContext("/sse") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use {
                it.write("event: text-delta\ndata: {\"delta\":\"a\"}\n\n".toByteArray()); it.flush()
                it.write("event: text-delta\ndata: {\"delta\":\"b\"}\n\n".toByteArray()); it.flush()
                it.write("event: finish\ndata: {\"finishReason\":\"stop\"}\n\n".toByteArray())
            }
        }
        server.createContext("/sse-fail") { ex ->
            val out = """{"error":{"kind":"NotFound","message":"Chat not found"}}""".toByteArray()
            ex.sendResponseHeaders(404, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.createContext("/sse-hang") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write("event: text-delta\ndata: {\"delta\":\"x\"}\n\n".toByteArray())
                ex.responseBody.flush()
                // Keep the stream open until the client goes away.
                while (true) {
                    Thread.sleep(50)
                    ex.responseBody.write(": ping\n".toByteArray())
                    ex.responseBody.flush()
                }
            } catch (_: Exception) {
                clientGone.countDown()
            }
        }
        server.createContext("/sse-silent") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write("event: text-delta\ndata: {\"delta\":\"x\"}\n\n".toByteArray())
                ex.responseBody.flush()
                // Total silence afterwards: nothing is ever written again, so only a
                // proper disconnect from the client can end this handler.
                val closed = CountDownLatch(1)
                Thread { try { ex.requestBody.read(); } catch (_: Exception) {} ; closed.countDown() }.start()
                // Probe for the client going away by attempting a write once the stall is over.
                Thread.sleep(8_000)
                ex.responseBody.write(": probe\n".toByteArray()); ex.responseBody.flush()
                ex.responseBody.write(": probe\n".toByteArray()); ex.responseBody.flush()
            } catch (_: Exception) {
                clientGone.countDown()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun executeSendsBodyAndHeadersAndReadsResponse() = runBlocking<Unit> {
        val response = engine.execute(
            HttpRequest("POST", "$base/echo", mapOf("Authorization" to "Bearer t0k"), """{"a":1}""".toByteArray(), "application/json"),
        )
        assertEquals(200, response.status)
        assertEquals("""{"ok":true}""", response.text())
        assertEquals("Bearer t0k", lastAuth.get())
        assertEquals("""{"a":1}""", lastBody.get())
    }

    @Test
    fun non2xxIsReturnedNotThrown() = runBlocking<Unit> {
        val response = engine.execute(HttpRequest("GET", "$base/fail"))
        assertEquals(400, response.status)
        assertTrue(response.text().contains("nope"))
    }

    @Test
    fun noContentHasEmptyBody() = runBlocking<Unit> {
        val response = engine.execute(HttpRequest("DELETE", "$base/empty"))
        assertEquals(204, response.status)
        assertEquals(0, response.body.size)
    }

    @Test
    fun connectionRefusedBecomesNetworkException() {
        // Reserve a free port, then close it so nothing listens there.
        val port = java.net.ServerSocket(0).use { it.localPort }
        try {
            runBlocking { engine.execute(HttpRequest("GET", "http://127.0.0.1:$port/x")) }
            error("expected failure")
        } catch (e: ApiException) {
            assertTrue(e.isNetwork)
        }
    }

    @Test
    fun streamEmitsFramesInOrder() = runBlocking<Unit> {
        val events = engine.streamLines(HttpRequest("POST", "$base/sse", body = "{}".toByteArray()))
            .sseFrames().streamEvents().toList()
        assertEquals(
            listOf(
                StreamEvent.TextDelta("a"),
                StreamEvent.TextDelta("b"),
                StreamEvent.Finish("stop", null, null, null),
            ),
            events,
        )
    }

    @Test
    fun streamHttpErrorThrowsParsedException() {
        try {
            runBlocking { engine.streamLines(HttpRequest("POST", "$base/sse-fail")).toList() }
            error("expected failure")
        } catch (e: ApiException) {
            assertTrue(e.isNotFound)
            assertEquals("Chat not found", e.message)
        }
    }

    @Test
    fun cancellingTheCollectorClosesTheConnection() = runBlocking<Unit> {
        val received = engine.streamLines(HttpRequest("POST", "$base/sse-hang", body = "{}".toByteArray()))
            .sseFrames().streamEvents()
        // Take the first event, then stop collecting: the engine must drop the socket.
        val first = received.first()
        assertEquals(StreamEvent.TextDelta("x"), first)
        assertTrue("server should notice the client disconnected", clientGone.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun cancellingAnInFlightCollectorAbortsPromptly() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) {
            engine.streamLines(HttpRequest("POST", "$base/sse-hang", body = "{}".toByteArray())).toList()
        }
        kotlinx.coroutines.delay(300)
        val started = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 3_000)
        assertTrue(clientGone.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun cancelDuringSilenceReturnsImmediatelyInsteadOfWaitingForReadTimeout() = runBlocking<Unit> {
        assumeTrue(promptCancelWhileSilent)
        // Read timeout is deliberately huge; only the watcher can unblock the read.
        val job = launch(Dispatchers.Default) {
            engine.streamLines(
                HttpRequest("POST", "$base/sse-silent", body = "{}".toByteArray(), readTimeoutMs = 5 * 60_000L),
            ).toList()
        }
        kotlinx.coroutines.delay(500) // first event delivered, now the stream is silent
        val started = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took ${elapsedMs}ms while the stream was silent", elapsedMs < 2_000)
    }

    @Test
    fun cancellationOfExecuteIsNotReportedAsNetworkError() = runBlocking<Unit> {
        val result = async(Dispatchers.Default) { runCatching { engine.execute(HttpRequest("GET", "$base/sse-hang")) } }
        kotlinx.coroutines.delay(300)
        result.cancel()
        try {
            result.await()
            error("expected cancellation")
        } catch (_: CancellationException) {
            // correct: surfaced as cancellation, not ApiException
        }
    }
}
