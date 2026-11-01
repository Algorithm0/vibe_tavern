package com.vibetavern.client.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One dispatched Server-Sent Events frame. */
data class SseFrame(val event: String, val data: String)

/**
 * Incremental parser for the `text/event-stream` format.
 *
 * Feed it one line at a time (without the line terminator). A blank line
 * dispatches the pending frame. Comment lines (`:`), `id:` and `retry:` are ignored.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseFrame? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Flushes a frame left pending when the stream ended without a trailing blank line. */
    fun finish(): SseFrame? = dispatch()

    private fun dispatch(): SseFrame? {
        if (!hasData && event == null) return null
        val frame = SseFrame(event ?: "message", data.toString())
        event = null
        data.setLength(0)
        hasData = false
        return frame
    }
}

/** Groups raw lines into SSE frames. */
fun Flow<String>.sseFrames(): Flow<SseFrame> = flow {
    val parser = SseParser()
    collect { line -> parser.feed(line)?.let { emit(it) } }
    parser.finish()?.let { emit(it) }
}

/**
 * Typed events of the generation streams (`/messages/stream`,
 * `/messages/:id/regenerate/stream`, `/generate-reply/stream`).
 *
 * The documented `snapshot` and `done` events are not emitted by the current
 * server; a stream simply ends after [Finish]. Clients refetch the chat then.
 */
sealed interface StreamEvent {
    /** The user's message was persisted (only on `/messages/stream`). */
    data class UserMessageSaved(val messageId: String) : StreamEvent

    data class GenerationMetadata(val modelId: String?, val presetName: String?) : StreamEvent

    data class ReasoningDelta(val delta: String) : StreamEvent

    data class TextDelta(val delta: String) : StreamEvent

    data class ReasoningDone(val durationMs: Long?) : StreamEvent

    data class Finish(
        val finishReason: String?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val totalTokens: Long?,
    ) : StreamEvent

    /** A generation failure reported in-band (provider error, missing chat, ...). */
    data class Error(val exception: ApiException) : StreamEvent

    /** Forward compatibility: an event this client does not know yet. */
    data class Unknown(val name: String, val data: String) : StreamEvent
}

object StreamEvents {

    fun decode(frame: SseFrame): StreamEvent {
        if (frame.event == "error") return StreamEvent.Error(ApiErrorParser.fromSseData(frame.data))

        val obj = parseObject(frame.data) ?: return StreamEvent.Unknown(frame.event, frame.data)
        return when (frame.event) {
            "user-message-saved" -> obj.string("messageId")
                ?.let { StreamEvent.UserMessageSaved(it) }
                ?: StreamEvent.Unknown(frame.event, frame.data)
            "generation-metadata" -> StreamEvent.GenerationMetadata(obj.string("modelId"), obj.string("presetName"))
            "reasoning-delta" -> StreamEvent.ReasoningDelta(obj.string("delta").orEmpty())
            "text-delta" -> StreamEvent.TextDelta(obj.string("delta").orEmpty())
            "reasoning-done" -> StreamEvent.ReasoningDone(obj.long("durationMs"))
            "finish" -> {
                val usage = obj["usage"] as? JsonObject
                StreamEvent.Finish(
                    finishReason = obj.string("finishReason"),
                    inputTokens = usage?.long("inputTokens"),
                    outputTokens = usage?.long("outputTokens"),
                    totalTokens = usage?.long("totalTokens"),
                )
            }
            else -> StreamEvent.Unknown(frame.event, frame.data)
        }
    }

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

fun Flow<SseFrame>.streamEvents(): Flow<StreamEvent> = map(StreamEvents::decode)
