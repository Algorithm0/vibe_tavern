package com.vibetavern.client.network

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseTest {

    private fun frames(text: String): List<SseFrame> = runBlocking {
        flowOf(*text.split("\n").toTypedArray()).sseFrames().toList()
    }

    @Test
    fun parsesEventAndData() {
        val result = frames("event: text-delta\ndata: {\"delta\":\"hi\"}\n\n")
        assertEquals(listOf(SseFrame("text-delta", "{\"delta\":\"hi\"}")), result)
    }

    @Test
    fun joinsMultipleDataLinesWithNewline() {
        val result = frames("data: a\ndata: b\n\n")
        assertEquals(listOf(SseFrame("message", "a\nb")), result)
    }

    @Test
    fun ignoresCommentsIdAndRetry() {
        val result = frames(": keep-alive\nid: 7\nretry: 100\nevent: x\ndata: 1\n\n")
        assertEquals(listOf(SseFrame("x", "1")), result)
    }

    @Test
    fun flushesFrameWithoutTrailingBlankLine() {
        val result = frames("event: finish\ndata: {}")
        assertEquals(listOf(SseFrame("finish", "{}")), result)
    }

    @Test
    fun blankLinesAloneProduceNothing() {
        assertTrue(frames("\n\n\n").isEmpty())
    }

    @Test
    fun parserKeepsStateBetweenFrames() {
        val parser = SseParser()
        assertNull(parser.feed("event: a"))
        assertNull(parser.feed("data: 1"))
        assertEquals(SseFrame("a", "1"), parser.feed(""))
        assertNull(parser.feed("data: 2"))
        assertEquals(SseFrame("message", "2"), parser.feed(""))
    }

    @Test
    fun dataWithoutSpaceAfterColon() {
        assertEquals(listOf(SseFrame("message", "x")), frames("data:x\n\n"))
    }

    // ── against real captured streams ────────────────────────────────────

    private fun decode(fixture: String): List<StreamEvent> = runBlocking {
        flowOf(*Fixtures.text(fixture).split("\n").toTypedArray()).sseFrames().streamEvents().toList()
    }

    @Test
    fun decodesCapturedSendStream() {
        val events = decode("sse_send.txt")
        assertEquals(StreamEvent.UserMessageSaved("msg_675e8f7383c7_0003"), events[0])
        assertEquals(StreamEvent.GenerationMetadata("mock-1", "Default"), events[1])
        assertEquals(StreamEvent.ReasoningDelta("thinking about a greeting"), events[2])
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.delta }
        assertEquals("*smiles* Hello, traveler. Welcome to the tower.", text)
        assertEquals(StreamEvent.ReasoningDone(149), events.single { it is StreamEvent.ReasoningDone })
        val finish = events.last() as StreamEvent.Finish
        assertEquals("stop", finish.finishReason)
        assertEquals(10L, finish.inputTokens)
        assertEquals(5L, finish.outputTokens)
        assertEquals(15L, finish.totalTokens)
    }

    @Test
    fun decodesCapturedRegenerateStreamWithoutUserMessageEvent() {
        val events = decode("sse_regenerate.txt")
        assertTrue(events.none { it is StreamEvent.UserMessageSaved })
        assertTrue(events.last() is StreamEvent.Finish)
    }

    @Test
    fun decodesCapturedProviderErrorEvent() {
        val events = decode("sse_error.txt")
        val error = events.last() as StreamEvent.Error
        assertTrue(error.exception.message!!.startsWith("Cannot connect to API"))
        assertEquals("unknown", error.exception.category)
    }

    @Test
    fun unknownEventsAreKeptNotDropped() {
        val event = StreamEvents.decode(SseFrame("brand-new", "{\"a\":1}"))
        assertEquals(StreamEvent.Unknown("brand-new", "{\"a\":1}"), event)
    }

    @Test
    fun malformedKnownEventBecomesUnknownInsteadOfCrashing() {
        val event = StreamEvents.decode(SseFrame("text-delta", "not json"))
        assertTrue(event is StreamEvent.Unknown)
    }

    @Test
    fun missingDeltaIsEmptyText() {
        assertEquals(StreamEvent.TextDelta(""), StreamEvents.decode(SseFrame("text-delta", "{}")))
    }
}
