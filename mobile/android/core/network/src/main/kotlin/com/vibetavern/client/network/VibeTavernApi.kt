package com.vibetavern.client.network

import com.vibetavern.client.model.Attachment
import com.vibetavern.client.model.AvatarUploadResult
import com.vibetavern.client.model.BootstrapState
import com.vibetavern.client.model.CharacterStatusResponse
import com.vibetavern.client.model.ImportResult
import com.vibetavern.client.model.MobileAccessInfo
import com.vibetavern.client.model.ProviderProfile
import com.vibetavern.client.model.RuntimeVersion
import com.vibetavern.client.model.SessionPatch
import com.vibetavern.client.model.UploadedAsset
import com.vibetavern.client.model.VtJson
import java.net.URLEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer

/**
 * Typed client for the Vibe Tavern HTTP API.
 *
 * Mutations return a [SessionPatch] (or a narrower typed result); merge it into
 * the local state with `SessionState.merge`. Generation endpoints return a cold
 * [Flow] of [StreamEvent]; collecting starts the request and cancelling the
 * collector aborts it.
 */
class VibeTavernApi(
    override val connection: ServerConnection,
    private val engine: HttpEngine,
) : TavernApi {

    // ── Runtime ──────────────────────────────────────────────────────────

    override suspend fun runtimeVersion(): RuntimeVersion =
        get("/api/runtime/version", RuntimeVersion.serializer())

    /** Everything the app needs on startup: characters, presets, UI settings and the initial chat. */
    override suspend fun bootstrap(): BootstrapState = get("/api/bootstrap", BootstrapState.serializer())

    override suspend fun mobileAccessInfo(): MobileAccessInfo =
        get("/api/settings/mobile-access", MobileAccessInfo.serializer())

    // ── Providers ────────────────────────────────────────────────────────

    override suspend fun listProviders(): List<ProviderProfile> =
        get("/api/providers", ListSerializer(ProviderProfile.serializer()))

    override suspend fun activateProvider(providerId: String): ProviderProfile =
        send("POST", "/api/providers/${enc(providerId)}/activate", null, ProviderProfile.serializer())

    // ── Chats ────────────────────────────────────────────────────────────

    override suspend fun getChat(chatId: String): SessionPatch =
        get("/api/chats/${enc(chatId)}", SessionPatch.serializer())

    /** Creates a chat with [characterId] (or the system character when null). */
    override suspend fun createChat(characterId: String?): SessionPatch =
        send(
            "POST", "/api/chats",
            buildJsonObject { if (characterId != null) put("characterId", characterId) },
            SessionPatch.serializer(),
        )

    override suspend fun deleteChat(chatId: String) {
        sendUnit("DELETE", "/api/chats/${enc(chatId)}", null)
    }

    override suspend fun renameChat(chatId: String, title: String): SessionPatch =
        send("PATCH", "/api/chats/${enc(chatId)}/title", buildJsonObject { put("title", title) }, SessionPatch.serializer())

    override suspend fun clearChat(chatId: String): SessionPatch =
        send("POST", "/api/chats/${enc(chatId)}/clear", null, SessionPatch.serializer())

    /** Forks the chat from [fromMessageId] (or its last message) into a new branch. */
    override suspend fun forkChat(chatId: String, fromMessageId: String?): SessionPatch =
        send(
            "POST", "/api/chats/${enc(chatId)}/fork",
            buildJsonObject { if (fromMessageId != null) put("fromMessageId", fromMessageId) },
            SessionPatch.serializer(),
        )

    override suspend fun setGreetingIndex(chatId: String, index: Int): SessionPatch =
        send(
            "PATCH", "/api/chats/${enc(chatId)}/greeting-index",
            buildJsonObject { put("greetingIndex", index) },
            SessionPatch.serializer(),
        )

    // ── Branches ─────────────────────────────────────────────────────────

    override suspend fun activateBranch(chatId: String, branchId: String): SessionPatch =
        send("POST", "/api/chats/${enc(chatId)}/branches/${enc(branchId)}/activate", null, SessionPatch.serializer())

    override suspend fun deleteBranch(chatId: String, branchId: String): SessionPatch =
        send("DELETE", "/api/chats/${enc(chatId)}/branches/${enc(branchId)}", null, SessionPatch.serializer())

    override suspend fun renameBranch(chatId: String, branchId: String, label: String): SessionPatch =
        send(
            "PATCH", "/api/chats/${enc(chatId)}/branches/${enc(branchId)}",
            buildJsonObject { put("label", label) },
            SessionPatch.serializer(),
        )

    /** Creates a new branch that ends at [messageId]. */
    override suspend fun branchFromMessage(chatId: String, messageId: String): SessionPatch =
        send("POST", "/api/chats/${enc(chatId)}/messages/${enc(messageId)}/branch", null, SessionPatch.serializer())

    // ── Messages ─────────────────────────────────────────────────────────

    /** Appends a user message without asking the model to reply. */
    override suspend fun appendMessage(chatId: String, content: String): SessionPatch =
        send("POST", "/api/chats/${enc(chatId)}/messages", sendBody(content, emptyList()), SessionPatch.serializer())

    /** Sends a user message and streams the model's reply. */
    override fun sendMessageStream(
        chatId: String,
        content: String,
        attachments: List<Attachment>,
    ): Flow<StreamEvent> =
        stream("/api/chats/${enc(chatId)}/messages/stream", sendBody(content, attachments))

    /** Re-rolls an assistant message as a new swipe. */
    override fun regenerateStream(chatId: String, messageId: String): Flow<StreamEvent> =
        stream("/api/chats/${enc(chatId)}/messages/${enc(messageId)}/regenerate/stream", JsonObject(emptyMap()))

    /** Asks the model to continue an assistant message in place. */
    override fun continueStream(chatId: String, messageId: String): Flow<StreamEvent> =
        stream("/api/chats/${enc(chatId)}/messages/${enc(messageId)}/continue/stream", JsonObject(emptyMap()))

    /** Asks the character to speak next without any user input. */
    override fun generateReplyStream(chatId: String): Flow<StreamEvent> =
        stream("/api/chats/${enc(chatId)}/generate-reply/stream", JsonObject(emptyMap()))

    override suspend fun editMessage(chatId: String, messageId: String, content: String): SessionPatch =
        send(
            "PATCH", "/api/chats/${enc(chatId)}/messages/${enc(messageId)}",
            buildJsonObject { put("content", content) },
            SessionPatch.serializer(),
        )

    override suspend fun deleteMessage(chatId: String, messageId: String): SessionPatch =
        send("DELETE", "/api/chats/${enc(chatId)}/messages/${enc(messageId)}", null, SessionPatch.serializer())

    override suspend fun selectVariant(chatId: String, messageId: String, variantIndex: Int): SessionPatch =
        send(
            "POST", "/api/chats/${enc(chatId)}/messages/${enc(messageId)}/variants/$variantIndex/select",
            null, SessionPatch.serializer(),
        )

    override suspend fun deleteVariant(chatId: String, messageId: String, variantIndex: Int): SessionPatch =
        send(
            "DELETE", "/api/chats/${enc(chatId)}/messages/${enc(messageId)}/variants/$variantIndex",
            null, SessionPatch.serializer(),
        )

    // ── Characters ───────────────────────────────────────────────────────

    /**
     * Creates a character. [fields] uses the server's create schema keys
     * (`name` is required; `description`, `firstMessage`, `scenario`, `tags`, ...).
     * The response carries the new character's first chat in `snapshot`.
     */
    override suspend fun createCharacter(fields: JsonObject): ImportResult =
        send("POST", "/api/characters", fields, ImportResult.serializer())

    /**
     * Updates a character. Only keys present in [patch] change; an explicit
     * [JsonNull] clears a nullable field. Returns the updated card in `character`.
     */
    override suspend fun updateCharacter(characterId: String, patch: JsonObject): SessionPatch =
        send("PATCH", "/api/characters/${enc(characterId)}", patch, SessionPatch.serializer())

    override suspend fun deleteCharacter(characterId: String) {
        sendUnit("DELETE", "/api/characters/${enc(characterId)}", null)
    }

    override suspend fun archiveCharacter(characterId: String): CharacterStatusResponse =
        send("PATCH", "/api/characters/${enc(characterId)}/archive", null, CharacterStatusResponse.serializer())

    override suspend fun unarchiveCharacter(characterId: String): CharacterStatusResponse =
        send("PATCH", "/api/characters/${enc(characterId)}/unarchive", null, CharacterStatusResponse.serializer())

    override suspend fun duplicateCharacter(characterId: String): ImportResult =
        send("POST", "/api/characters/${enc(characterId)}/duplicate", null, ImportResult.serializer())

    /** Uploads the (already cropped) avatar; [full] is the optional uncropped original. */
    override suspend fun uploadAvatar(
        characterId: String,
        crop: ByteArray,
        mimeType: String,
        full: ByteArray?,
    ): AvatarUploadResult {
        val body = MultipartBuilder().apply {
            file("crop", "avatar", mimeType, crop)
            if (full != null) file("full", "avatar-full", mimeType, full)
        }.build()
        return sendMultipart("/api/characters/${enc(characterId)}/avatar", body, AvatarUploadResult.serializer())
    }

    /** Character card as a SillyTavern-compatible PNG. */
    override suspend fun exportCharacterPng(characterId: String): ByteArray {
        val response = execute("GET", "/api/characters/${enc(characterId)}/export", null)
        return response.body
    }

    /**
     * Imports a card. Pass the output of [PngCard.extract] (or a plain `.json` file's text
     * as [jsonText]); a [monolithText] is preferred by the server when both are given.
     */
    override suspend fun importCharacter(
        fileName: String,
        jsonText: String?,
        monolithText: String?,
        importEmbeddedBook: Boolean,
    ): ImportResult {
        require(jsonText != null || monolithText != null) { "Nothing to import" }
        val body = buildJsonObject {
            put("fileName", fileName)
            if (jsonText != null) put("jsonText", jsonText)
            if (monolithText != null) put("monolithText", monolithText)
            put("importEmbeddedBook", importEmbeddedBook)
        }
        return send("POST", "/api/import/json", body, ImportResult.serializer())
    }

    // ── Assets ───────────────────────────────────────────────────────────

    /** Uploads a chat attachment (image, audio, text file) and returns its asset id. */
    override suspend fun uploadAsset(fileName: String, mimeType: String, bytes: ByteArray): UploadedAsset {
        val body = MultipartBuilder().file("file", fileName, mimeType, bytes).build()
        return sendMultipart("/api/assets/upload", body, UploadedAsset.serializer())
    }

    // ── URLs for image loading ───────────────────────────────────────────

    /**
     * Avatar thumbnail URL. [version] (the character's `updatedAt`) busts caches.
     * Loaded by an image library that cannot set headers, so the token travels as
     * `?token=`, which the server accepts for every API route.
     */
    override fun characterAvatarUrl(characterId: String, version: String?, full: Boolean): String {
        val path = "/api/characters/${enc(characterId)}/avatar" + if (full) "/full" else ""
        return urlWithAuth(path, version?.let { "v" to it })
    }

    /** Chat attachment URL. Asset reads are public, so no token is attached. */
    override fun assetUrl(assetId: String): String = connection.baseUrl + "/api/assets/${enc(assetId)}"

    private fun urlWithAuth(path: String, extra: Pair<String, String>?): String {
        val params = buildList {
            extra?.let { add("${it.first}=${URLEncoder.encode(it.second, "UTF-8")}") }
            connection.token?.let { add("token=${URLEncoder.encode(it, "UTF-8")}") }
        }
        return connection.baseUrl + path + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private fun sendBody(content: String, attachments: List<Attachment>): JsonObject = buildJsonObject {
        put("content", content)
        if (attachments.isNotEmpty()) {
            put(
                "attachments",
                JsonArray(
                    attachments.map { a ->
                        buildJsonObject {
                            put("id", a.id)
                            put("assetId", a.assetId)
                            put("type", a.type)
                            put("name", a.name)
                            put("mimeType", a.mimeType)
                            put("sizeBytes", a.sizeBytes)
                            a.durationMs?.let { put("durationMs", it) }
                            a.tokenCount?.let { put("tokenCount", it) }
                        }
                    },
                ),
            )
        }
    }

    private fun stream(path: String, body: JsonObject): Flow<StreamEvent> =
        engine.streamLines(request("POST", path, body, readTimeoutMs = HttpRequest.STREAM_READ_TIMEOUT_MS))
            .sseFrames()
            .streamEvents()

    private suspend fun <T> get(path: String, serializer: DeserializationStrategy<T>): T =
        send("GET", path, null, serializer)

    private suspend fun <T> send(
        method: String,
        path: String,
        body: JsonElement?,
        serializer: DeserializationStrategy<T>,
    ): T {
        val response = execute(method, path, body)
        return decode(response, serializer)
    }

    private suspend fun sendUnit(method: String, path: String, body: JsonElement?) {
        execute(method, path, body)
    }

    private suspend fun <T> sendMultipart(path: String, body: MultipartBody, serializer: DeserializationStrategy<T>): T {
        val request = HttpRequest(
            method = "POST",
            url = connection.baseUrl + path,
            headers = authHeaders(),
            body = body.bytes,
            contentType = body.contentType,
        )
        return decode(ensureOk(engine.execute(request)), serializer)
    }

    private suspend fun execute(method: String, path: String, body: JsonElement?): HttpResponse =
        ensureOk(engine.execute(request(method, path, body)))

    private fun request(
        method: String,
        path: String,
        body: JsonElement?,
        readTimeoutMs: Long = HttpRequest.DEFAULT_READ_TIMEOUT_MS,
    ): HttpRequest = HttpRequest(
        method = method,
        url = connection.baseUrl + path,
        headers = authHeaders() + ("Accept" to if (readTimeoutMs == HttpRequest.STREAM_READ_TIMEOUT_MS) "text/event-stream" else "application/json"),
        body = body?.let { VtJson.encodeToString(JsonElement.serializer(), it).toByteArray(Charsets.UTF_8) },
        contentType = if (body != null) "application/json" else null,
        readTimeoutMs = readTimeoutMs,
    )

    private fun authHeaders(): Map<String, String> =
        connection.token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    private fun ensureOk(response: HttpResponse): HttpResponse {
        if (!response.isSuccessful) throw ApiErrorParser.parse(response.status, response.text())
        return response
    }

    private fun <T> decode(response: HttpResponse, serializer: DeserializationStrategy<T>): T = try {
        VtJson.decodeFromString(serializer, response.text())
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiException(
            status = response.status,
            kind = "BadResponse",
            message = "Unexpected response from the server: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}",
            cause = e,
        )
    }

    private fun enc(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}
