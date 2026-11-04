package com.vibetavern.client.data

import com.vibetavern.client.model.ChatDto
import com.vibetavern.client.model.MessageDto
import com.vibetavern.client.model.MessageVariant
import com.vibetavern.client.model.SessionPatch
import com.vibetavern.client.network.ApiException
import com.vibetavern.client.network.StreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val api = FakeTavernApi()
    private val controller = ChatController(api, scope)

    @After
    fun tearDown() = scope.cancel()

    private fun msg(id: String, role: String, text: String, vararg variants: String) = MessageDto(
        id = id, chatId = "c1", role = role, content = text, position = 0,
        variants = (variants.toList().ifEmpty { listOf(text) }).mapIndexed { i, v ->
            MessageVariant(id = "$id-v$i", messageId = id, variantIndex = i, content = v, isSelected = i == 0)
        },
        selectedVariantIndex = 0,
    )

    private fun chat(vararg messages: MessageDto) = SessionPatch(
        activeChat = ChatDto(id = "c1", title = "Chat"),
        messages = messages.toList(),
    )

    private suspend fun awaitState(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(5_000) { controller.state.first(predicate) }

    @Test
    fun openLoadsTheChat() = runBlocking<Unit> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.onGetChat = { gate.await(); chat(msg("m1", "assistant", "Hello")) }
        controller.open("c1")
        assertTrue(controller.state.value.loading)   // held open by the gate, so this is deterministic
        gate.complete(Unit)
        val s = awaitState { !it.loading && it.session.messages.isNotEmpty() }
        assertEquals("Hello", s.session.messages.single().content)
        assertEquals("c1", s.chatId)
    }

    @Test
    fun loadFailureIsShownAndClearable() = runBlocking<Unit> {
        api.onGetChat = { throw ApiException(0, ApiException.KIND_NETWORK, "refused") }
        controller.open("c1")
        val s = awaitState { it.error != null && !it.loading }
        assertTrue(s.error!!.contains("reach the server"))
        controller.dismissError()
        assertNull(controller.state.value.error)
    }

    @Test
    fun sendStreamsLiveThenReloadsStoredMessages() = runBlocking<Unit> {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onSend = { _, _, _ -> events.receiveAsFlow() }
        controller.open("c1"); awaitState { !it.loading }

        api.onGetChat = { chat(msg("m1", "assistant", "Hi"), msg("m2", "user", "Hello"), msg("m3", "assistant", "Welcome, traveler.")) }
        controller.send("Hello")
        val started = awaitState { it.streaming != null }
        assertEquals("Hello", started.streaming!!.pendingUserText)
        assertEquals(StreamingReply.Kind.SEND, started.streaming!!.kind)

        events.send(StreamEvent.UserMessageSaved("m2"))
        events.send(StreamEvent.GenerationMetadata("mock-1", "Default"))
        events.send(StreamEvent.ReasoningDelta("hmm "))
        events.send(StreamEvent.ReasoningDelta("ok"))
        events.send(StreamEvent.ReasoningDone(100))
        events.send(StreamEvent.TextDelta("Welcome, "))
        events.send(StreamEvent.TextDelta("traveler."))
        val live = awaitState { it.streaming?.text == "Welcome, traveler." }
        assertEquals("hmm ok", live.streaming!!.reasoning)
        assertTrue(live.streaming!!.reasoningDone)
        assertEquals("mock-1", live.streaming!!.modelId)
        assertEquals(1, live.session.messages.size)                 // stored messages untouched while streaming

        events.send(StreamEvent.Finish("stop", 10, 5, 15)); events.close()
        val done = awaitState { it.streaming == null && it.session.messages.size == 3 }
        assertEquals("Welcome, traveler.", done.session.messages.last().content)
        assertNull(done.error)
    }

    @Test
    fun stopCancelsTheRequestAndShowsWhateverTheServerKept() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onSend = { _, _, _ ->
            flow {
                emit(StreamEvent.TextDelta("*smiles* "))
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        controller.open("c1"); awaitState { !it.loading }
        // The server stores the user message and the partial reply when the client disconnects.
        api.onGetChat = { chat(msg("m1", "assistant", "Hi"), msg("m2", "user", "cancel me"), msg("m3", "assistant", "*smiles* ")) }

        controller.send("cancel me")
        awaitState { it.streaming?.text == "*smiles* " }
        controller.stop()

        withTimeout(5_000) { cancelled.await() }
        val s = awaitState { it.streaming == null && it.session.messages.size == 3 }
        assertEquals("*smiles* ", s.session.messages.last().content)
        assertNull(s.error)                                          // Stop is not an error
    }

    @Test
    fun errorEventIsReportedAndStateStillReloaded() = runBlocking<Unit> {
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onSend = { _, _, _ -> flow { emit(StreamEvent.Error(ApiException(0, "Stream", "Cannot connect to API", "unknown"))) } }
        controller.open("c1"); awaitState { !it.loading }
        api.onGetChat = { chat(msg("m1", "assistant", "Hi"), msg("m2", "user", "hey")) }

        controller.send("hey")
        val s = awaitState { it.streaming == null && it.session.messages.size == 2 }
        // The load that follows must not wipe the generation error.
        assertEquals("Cannot connect to API", s.error)
    }

    @Test
    fun networkLossMidStreamBecomesAReadableError() = runBlocking<Unit> {
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onSend = { _, _, _ -> flow { emit(StreamEvent.TextDelta("par")); throw ApiException.network(java.io.IOException("reset")) } }
        controller.open("c1"); awaitState { !it.loading }
        controller.send("hey")
        val s = awaitState { it.streaming == null && it.error != null }
        assertTrue(s.error!!.contains("reach the server"))
    }

    @Test
    fun aSecondGenerationIsIgnoredWhileOneRuns() = runBlocking<Unit> {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onSend = { _, _, _ -> events.receiveAsFlow() }
        controller.open("c1"); awaitState { !it.loading }
        controller.send("one"); awaitState { it.streaming != null }
        controller.send("two"); controller.regenerate("m1"); controller.generateReply()
        events.close()
        awaitState { it.streaming == null }
        assertEquals(listOf("send:c1:one"), api.calls.filter { !it.startsWith("getChat") })
    }

    @Test
    fun blankMessageWithoutAttachmentsIsNotSent() = runBlocking<Unit> {
        api.onGetChat = { chat() }
        controller.open("c1"); awaitState { !it.loading }
        controller.send("   ")
        assertNull(controller.state.value.streaming)
        assertTrue(api.calls.none { it.startsWith("send") })
    }

    @Test
    fun regenerateAndContinueTargetTheMessage() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onRegenerate = { _, _ -> flow { gate.await(); emit(StreamEvent.TextDelta("again")) } }
        api.onContinue = { _, _ -> flow { gate.await() } }
        controller.open("c1"); awaitState { !it.loading }

        controller.regenerate("m1")
        val s = awaitState { it.streaming != null }
        assertEquals(StreamingReply.Kind.REGENERATE, s.streaming!!.kind)
        assertEquals("m1", s.streaming!!.targetMessageId)
        gate.complete(Unit)
        awaitState { it.streaming == null }

        controller.continueMessage("m1")
        assertEquals(StreamingReply.Kind.CONTINUE, awaitState { it.streaming != null || api.calls.contains("continue:m1") }.streaming?.kind ?: StreamingReply.Kind.CONTINUE)
        awaitState { it.streaming == null }
        assertTrue(api.calls.contains("continue:m1"))
    }

    @Test
    fun mutationsMergeOnlyWhatTheServerReturned() = runBlocking<Unit> {
        api.onGetChat = { chat(msg("m1", "assistant", "Hi", "Hi", "Hey")) }
        controller.open("c1"); awaitState { !it.loading }
        val titleBefore = controller.state.value.session.activeChat!!.title
        api.onSelectVariant = { _, _, idx ->
            SessionPatch(messages = listOf(msg("m1", "assistant", "Hey", "Hi", "Hey").copy(selectedVariantIndex = idx, content = "Hey")))
        }
        controller.selectVariant("m1", 1)
        val s = awaitState { it.session.messages.first().content == "Hey" }
        assertEquals(1, s.session.messages.first().selectedVariantIndex)
        assertEquals(titleBefore, s.session.activeChat!!.title)       // absent in the patch → preserved
    }

    @Test
    fun mutationFailureKeepsStateAndReportsTheServerMessage() = runBlocking<Unit> {
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        controller.open("c1"); awaitState { !it.loading }
        api.onEdit = { _, _, _ -> throw ApiException(409, "Conflict", "Message changed on another device") }
        controller.editMessage("m1", "x")
        val s = awaitState { it.error != null }
        assertEquals("Message changed on another device", s.error)
        assertEquals("Hi", s.session.messages.single().content)
    }

    @Test
    fun mutationsAreRefusedWhileGenerating() = runBlocking<Unit> {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        api.onGetChat = { chat(msg("m1", "assistant", "Hi")) }
        api.onSend = { _, _, _ -> events.receiveAsFlow() }
        var edited = false
        api.onEdit = { _, _, _ -> edited = true; chat() }
        controller.open("c1"); awaitState { !it.loading }
        controller.send("go"); awaitState { it.streaming != null }
        controller.editMessage("m1", "nope"); controller.deleteMessage("m1")
        delay(150)
        assertFalse(edited)
        events.close(); awaitState { it.streaming == null }
    }

    @Test
    fun openingAnotherChatDiscardsStaleResponses() = runBlocking<Unit> {
        val slow = CompletableDeferred<SessionPatch>()
        api.onGetChat = { id -> if (id == "old") slow.await() else chat(msg("n1", "assistant", "new chat")) }
        controller.open("old")
        controller.open("new")
        awaitState { it.chatId == "new" && it.session.messages.isNotEmpty() }
        slow.complete(chat(msg("o1", "assistant", "old chat")))           // arrives late
        delay(200)
        val s = controller.state.value
        assertEquals("new chat", s.session.messages.single().content)
        assertFalse(s.loading)
    }

    @Test
    fun openWithSeedsStateWithoutFetching() = runBlocking<Unit> {
        val seeded = com.vibetavern.client.model.SessionState(messages = listOf(msg("m1", "assistant", "seeded")))
        controller.openWith("c1", seeded)
        assertEquals("seeded", controller.state.value.session.messages.single().content)
        assertTrue(api.calls.isEmpty())
        assertNotNull(controller.state.value.chatId)
    }

    @Test
    fun describeGivesPeopleReadableMessages() {
        assertTrue(ChatController.describe(ApiException.network(java.io.IOException("x"))).contains("reach the server"))
        assertTrue(ChatController.describe(ApiException(401, "Unauthorized", "Invalid or missing token")).contains("token"))
        assertEquals("No active provider profile.", ChatController.describe(ApiException(400, "Validation", "No active provider profile.")))
    }
}
