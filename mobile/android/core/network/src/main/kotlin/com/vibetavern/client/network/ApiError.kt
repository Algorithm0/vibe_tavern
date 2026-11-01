package com.vibetavern.client.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Any failure talking to a Vibe Tavern server: HTTP errors, validation errors,
 * SSE `error` events and plain network failures all end up here.
 *
 * [status] is 0 for failures that never produced an HTTP response.
 */
class ApiException(
    val status: Int,
    val kind: String?,
    message: String,
    val category: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val isUnauthorized: Boolean get() = status == 401 || kind == KIND_UNAUTHORIZED
    val isNotFound: Boolean get() = status == 404 || kind == KIND_NOT_FOUND
    val isNetwork: Boolean get() = kind == KIND_NETWORK

    companion object {
        const val KIND_NETWORK = "Network"
        const val KIND_UNAUTHORIZED = "Unauthorized"
        const val KIND_NOT_FOUND = "NotFound"
        const val KIND_VALIDATION = "Validation"
        const val KIND_INTERNAL = "Internal"

        fun network(cause: Throwable): ApiException =
            ApiException(0, KIND_NETWORK, cause.message ?: "Network error", cause = cause)
    }
}

/**
 * Turns the server's error payloads into [ApiException].
 *
 * The server uses several shapes (verified against a live instance; the API
 * reference only documents the first):
 *  - `{"error":{"kind":"Validation","message":"..."}}` — domain errors
 *  - `{"error":"Route not found: GET /x"}` — router-level errors
 *  - `{"success":false,"error":{"name":"ZodError","message":"[...]"}}` — body validation
 *  - SSE `event: error` / `data: {"message":"...","category":"unknown"}`
 */
object ApiErrorParser {

    fun parse(status: Int, body: String): ApiException {
        val root = parseObject(body)
        if (root == null) {
            val text = body.trim().take(300)
            return ApiException(status, kindForStatus(status), text.ifEmpty { defaultMessage(status) })
        }
        val error = root["error"]
        return when {
            error is JsonObject -> fromErrorObject(status, error)
            error is JsonPrimitive && error.contentOrNull != null ->
                ApiException(status, kindForStatus(status), error.content)
            root["message"] != null -> fromSseObject(status, root)
            else -> ApiException(status, kindForStatus(status), defaultMessage(status))
        }
    }

    /** `data:` payload of an SSE `error` event. */
    fun fromSseData(data: String): ApiException {
        val root = parseObject(data)
            ?: return ApiException(0, KIND_STREAM, data.trim().ifEmpty { "Stream error" })
        return fromSseObject(0, root)
    }

    private const val KIND_STREAM = "Stream"

    private fun fromSseObject(status: Int, root: JsonObject): ApiException = ApiException(
        status = status,
        kind = KIND_STREAM,
        message = root.string("message") ?: "Stream error",
        category = root.string("category"),
    )

    private fun fromErrorObject(status: Int, error: JsonObject): ApiException {
        val kind = error.string("kind")
        val name = error.string("name")
        val rawMessage = error.string("message") ?: defaultMessage(status)
        return if (name == "ZodError") {
            ApiException(status, ApiException.KIND_VALIDATION, humanizeZod(rawMessage))
        } else {
            ApiException(status, kind ?: kindForStatus(status), rawMessage)
        }
    }

    /** Zod puts a JSON array of issues in `message`; reduce it to "field: reason". */
    internal fun humanizeZod(message: String): String {
        val issues = try {
            Json.parseToJsonElement(message) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return message
        val parts = issues.mapNotNull { issue ->
            val obj = issue as? JsonObject ?: return@mapNotNull null
            val text = obj.string("message") ?: return@mapNotNull null
            val path = (obj["path"] as? JsonArray)?.joinToString(".") { it.jsonPrimitive.content }.orEmpty()
            if (path.isEmpty()) text else "$path: $text"
        }
        return parts.joinToString("; ").ifEmpty { message }
    }

    private fun kindForStatus(status: Int): String? = when (status) {
        400 -> ApiException.KIND_VALIDATION
        401 -> ApiException.KIND_UNAUTHORIZED
        404 -> ApiException.KIND_NOT_FOUND
        409 -> "Conflict"
        499 -> "Cancelled"
        502 -> "Provider"
        in 500..599 -> ApiException.KIND_INTERNAL
        else -> null
    }

    private fun defaultMessage(status: Int): String = "Request failed (HTTP $status)"

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        return (element as? JsonPrimitive)?.contentOrNull
    }
}
