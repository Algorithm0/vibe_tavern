package com.vibetavern.client.data

import com.vibetavern.client.model.Attachment
import com.vibetavern.client.model.SessionPatch
import com.vibetavern.client.model.SessionState
import com.vibetavern.client.network.ApiException
import com.vibetavern.client.network.StreamEvent
import com.vibetavern.client.network.TavernApi
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the model is producing right now. Shown live, replaced by the stored message when done. */
data class StreamingReply(
    val kind: Kind,
    /** For [Kind.REGENERATE] / [Kind.CONTINUE]: the message being worked on. */
    val targetMessageId: String? = null,
    /** For [Kind.SEND]: the user's text, shown as a pending bubble until the chat is reloaded. */
    val pendingUserText: String? = null,
    val pendingAttachments: List<Attachment> = emptyList(),
    val text: String = "",
    val reasoning: String = "",
    val reasoningDone: Boolean = false,
    val modelId: String? = null,
) {
    enum class Kind { SEND, REGENERATE, CONTINUE, REPLY }
}

data class ChatUiState(
    val chatId: String? = null,
    val session: SessionState = SessionState(),
    /** True while the first load of a chat is in flight. */
    val loading: Boolean = false,
    val streaming: StreamingReply? = null,
    /** Last failure, human readable. Cleared by [ChatController.dismissError] or the next action. */
    val error: String? = null,
) {
    val isGenerating: Boolean get() = streaming != null
}

/**
 * Drives one open chat: loading, sending with live streaming, stopping, swipes, edits,
 * branches. Server responses are merged with "absent → preserve" semantics
 * ([SessionState.merge]), so every call updates exactly the regions it touched.
 *
 * Streaming never edits the stored messages. It is shown from [ChatUiState.streaming]
 * and, when the stream ends for any reason (finish, error, Stop, network loss), the
 * chat is reloaded from the server, which is the single source of truth. That is also
 * what makes Stop correct: the server keeps the user's message and stores the partial
 * reply as an ordinary message, and the reload simply shows it.
 */
class ChatController(
    private val api: TavernApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Serialises non-streaming mutations so responses are merged in request order. */
    private val mutations = Mutex()
    private var generation: Job? = null
    private val epoch = AtomicLong()

    // ── Loading ──────────────────────────────────────────────────────────

    /** Opens [chatId], discarding whatever was open (including a running generation's view). */
    fun open(chatId: String) {
        epoch.incrementAndGet()
        generation?.cancel()
        _state.value = ChatUiState(chatId = chatId, loading = true)
        val myEpoch = epoch.get()
        scope.launch { load(chatId, myEpoch, showErrorFor = chatId) }
    }

    /** Seeds the state from data we already have (e.g. the bootstrap snapshot) and skips the first fetch. */
    fun openWith(chatId: String, initial: SessionState) {
        epoch.incrementAndGet()
        generation?.cancel()
        _state.value = ChatUiState(chatId = chatId, session = initial)
    }

    fun refresh() {
        val id = _state.value.chatId ?: return
        val myEpoch = epoch.get()
        scope.launch { load(id, myEpoch, showErrorFor = id) }
    }

    /**
     * [myEpoch] must be read when the load was *requested*, not when its coroutine starts,
     * otherwise a late-starting stale load adopts the newer epoch and overwrites the new chat.
     * [keepError]: the reload that follows a generation must not wipe the generation's own
     * failure (no provider, server unreachable, ...), which is what the person needs to see.
     */
    private suspend fun load(chatId: String, myEpoch: Long, showErrorFor: String, keepError: Boolean = false) {
        try {
            val patch = api.getChat(chatId)
            if (myEpoch == epoch.get()) merge(patch, keepError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (myEpoch == epoch.get() && _state.value.chatId == showErrorFor) fail(e)
        } finally {
            if (myEpoch == epoch.get()) _state.update { it.copy(loading = false) }
        }
    }

    // ── Generation ───────────────────────────────────────────────────────

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val chatId = _state.value.chatId ?: return
        if (text.isBlank() && attachments.isEmpty()) return
        startGeneration(
            StreamingReply(StreamingReply.Kind.SEND, pendingUserText = text, pendingAttachments = attachments),
        ) { api.sendMessageStream(chatId, text, attachments) }
    }

    fun regenerate(messageId: String) {
        val chatId = _state.value.chatId ?: return
        startGeneration(StreamingReply(StreamingReply.Kind.REGENERATE, targetMessageId = messageId)) {
            api.regenerateStream(chatId, messageId)
        }
    }

    fun continueMessage(messageId: String) {
        val chatId = _state.value.chatId ?: return
        startGeneration(StreamingReply(StreamingReply.Kind.CONTINUE, targetMessageId = messageId)) {
            api.continueStream(chatId, messageId)
        }
    }

    /** Lets the character speak next without any user input. */
    fun generateReply() {
        val chatId = _state.value.chatId ?: return
        startGeneration(StreamingReply(StreamingReply.Kind.REPLY)) { api.generateReplyStream(chatId) }
    }

    /** Stops the running generation. Safe to call when nothing is running. */
    fun stop() {
        generation?.cancel()
    }

    private fun startGeneration(initial: StreamingReply, events: () -> Flow<StreamEvent>) {
        val chatId = _state.value.chatId ?: return
        // One generation at a time; the UI disables the buttons, this guards races.
        if (_state.value.isGenerating) return

        _state.update { it.copy(streaming = initial, error = null) }
        val myEpoch = epoch.get()
        generation = scope.launch {
            try {
                events().collect { event -> if (myEpoch == epoch.get()) apply(event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (myEpoch == epoch.get()) fail(e)
            } finally {
                // Runs on Stop too (the job is cancelled), hence NonCancellable.
                withContext(NonCancellable) {
                    if (myEpoch == epoch.get()) {
                        _state.update { it.copy(streaming = null) }
                        load(chatId, myEpoch, showErrorFor = chatId, keepError = true)
                    }
                }
            }
        }
    }

    private fun apply(event: StreamEvent) {
        when (event) {
            is StreamEvent.GenerationMetadata -> updateStreaming { it.copy(modelId = event.modelId) }
            is StreamEvent.ReasoningDelta -> updateStreaming { it.copy(reasoning = it.reasoning + event.delta) }
            is StreamEvent.ReasoningDone -> updateStreaming { it.copy(reasoningDone = true) }
            is StreamEvent.TextDelta -> updateStreaming { it.copy(text = it.text + event.delta) }
            is StreamEvent.Error -> fail(event.exception)
            is StreamEvent.UserMessageSaved, is StreamEvent.Finish, is StreamEvent.Unknown -> Unit
        }
    }

    private fun updateStreaming(transform: (StreamingReply) -> StreamingReply) {
        _state.update { s -> s.streaming?.let { s.copy(streaming = transform(it)) } ?: s }
    }

    // ── Mutations ────────────────────────────────────────────────────────

    fun selectVariant(messageId: String, variantIndex: Int) = mutate { chatId -> api.selectVariant(chatId, messageId, variantIndex) }

    fun deleteVariant(messageId: String, variantIndex: Int) = mutate { chatId -> api.deleteVariant(chatId, messageId, variantIndex) }

    fun editMessage(messageId: String, content: String) = mutate { chatId -> api.editMessage(chatId, messageId, content) }

    fun deleteMessage(messageId: String) = mutate { chatId -> api.deleteMessage(chatId, messageId) }

    fun renameChat(title: String) = mutate { chatId -> api.renameChat(chatId, title) }

    fun clearChat() = mutate { chatId -> api.clearChat(chatId) }

    fun setGreeting(index: Int) = mutate { chatId -> api.setGreetingIndex(chatId, index) }

    fun branchFrom(messageId: String) = mutate { chatId -> api.branchFromMessage(chatId, messageId) }

    fun forkFrom(messageId: String?) = mutate { chatId -> api.forkChat(chatId, messageId) }

    fun activateBranch(branchId: String) = mutate { chatId -> api.activateBranch(chatId, branchId) }

    fun renameBranch(branchId: String, label: String) = mutate { chatId -> api.renameBranch(chatId, branchId, label) }

    fun deleteBranch(branchId: String) = mutate { chatId -> api.deleteBranch(chatId, branchId) }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun mutate(call: suspend (chatId: String) -> SessionPatch) {
        val chatId = _state.value.chatId ?: return
        // Editing history under a running generation would race the server's own writes.
        if (_state.value.isGenerating) return
        val myEpoch = epoch.get()
        scope.launch {
            mutations.withLock {
                try {
                    val patch = call(chatId)
                    if (myEpoch == epoch.get()) merge(patch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (myEpoch == epoch.get()) fail(e)
                }
            }
        }
    }

    private fun merge(patch: SessionPatch, keepError: Boolean = false) {
        _state.update { it.copy(session = it.session.merge(patch), error = if (keepError) it.error else null) }
    }

    private fun fail(e: ApiException) {
        _state.update { it.copy(error = describe(e)) }
    }

    companion object {
        /** Messages a person can act on; raw transport details are hidden. */
        fun describe(e: ApiException): String = when {
            e.isNetwork -> "Can't reach the server. Check the connection and try again."
            e.isUnauthorized -> "The server rejected the access token."
            else -> e.message ?: "Something went wrong."
        }
    }
}
