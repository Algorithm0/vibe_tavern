package com.vibetavern.client.network

import com.vibetavern.client.model.Attachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeEngine : HttpEngine {
    val requests = mutableListOf<HttpRequest>()
    var next: HttpResponse = HttpResponse(200, body = "{}".toByteArray())
    var lines: List<String> = emptyList()

    fun respond(status: Int, body: String) { next = HttpResponse(status, body = body.toByteArray()) }

    override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return next }
    override fun streamLines(request: HttpRequest): Flow<String> { requests += request; return flowOf(*lines.toTypedArray()) }
}

class VibeTavernApiTest {

    private val engine = FakeEngine()
    private val remote = VibeTavernApi(ServerConnection("http://192.168.1.5:8787", token = "t0k"), engine)
    private val local = VibeTavernApi(ServerConnection(ServerAddress.LOCAL_BASE_URL), engine)

    private fun body(i: Int = 0): JsonObject =
        Json.parseToJsonElement(engine.requests[i].body!!.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun remoteRequestsCarryBearerTokenAndLocalOnesDoNot() = runBlocking<Unit> {
        engine.respond(200, Fixtures.text("bootstrap_full.json"))
        remote.bootstrap(); local.bootstrap()
        assertEquals("Bearer t0k", engine.requests[0].headers["Authorization"])
        assertNull(engine.requests[1].headers["Authorization"])
        assertEquals("http://192.168.1.5:8787/api/bootstrap", engine.requests[0].url)
    }

    @Test
    fun bootstrapDecodes() = runBlocking<Unit> {
        engine.respond(200, Fixtures.text("bootstrap_full.json"))
        val b = local.bootstrap()
        assertTrue(b.snapshot!!.messages!!.isNotEmpty())
    }

    @Test
    fun errorStatusBecomesApiException() = runBlocking<Unit> {
        engine.respond(400, """{"error":{"kind":"Validation","message":"No active provider profile. Activate one in Provider settings."}}""")
        try {
            local.appendMessage("c1", "hi"); fail()
        } catch (e: ApiException) {
            assertEquals("Validation", e.kind)
            assertTrue(e.message!!.startsWith("No active provider"))
        }
    }

    @Test
    fun unauthorizedIsRecognised() = runBlocking<Unit> {
        engine.respond(401, """{"error":{"kind":"Unauthorized","message":"Invalid or missing token"}}""")
        try { remote.bootstrap(); fail() } catch (e: ApiException) { assertTrue(e.isUnauthorized) }
    }

    @Test
    fun malformedSuccessBodyIsReportedNotCrashed() = runBlocking<Unit> {
        engine.respond(200, "<html>proxy login</html>")
        try { local.bootstrap(); fail() } catch (e: ApiException) { assertEquals("BadResponse", e.kind) }
    }

    @Test
    fun deleteWithNoContentSucceeds() = runBlocking<Unit> {
        engine.respond(204, "")
        local.deleteCharacter("char 1/../x")
        assertEquals("DELETE", engine.requests[0].method)
        assertEquals("http://127.0.0.1:8787/api/characters/char%201%2F..%2Fx", engine.requests[0].url) // path segments are escaped
    }

    @Test
    fun createChatOmitsCharacterWhenNull() = runBlocking<Unit> {
        engine.respond(200, Fixtures.text("chat_create.json"))
        local.createChat(null); local.createChat("char_1")
        assertTrue(body(0).isEmpty())
        assertEquals("char_1", body(1)["characterId"]!!.jsonPrimitive.content)
    }

    @Test
    fun mutationEndpointsUseTheDocumentedVerbsAndPaths() = runBlocking<Unit> {
        engine.respond(200, "{}")
        local.renameChat("c", "T"); local.forkChat("c", "m"); local.setGreetingIndex("c", 2)
        local.activateBranch("c", "b"); local.renameBranch("c", "b", "L"); local.editMessage("c", "m", "x")
        local.selectVariant("c", "m", 1); local.deleteVariant("c", "m", 1); local.branchFromMessage("c", "m")
        val seen = engine.requests.map { it.method + " " + it.url.removePrefix("http://127.0.0.1:8787") }
        assertEquals(
            listOf(
                "PATCH /api/chats/c/title", "POST /api/chats/c/fork", "PATCH /api/chats/c/greeting-index",
                "POST /api/chats/c/branches/b/activate", "PATCH /api/chats/c/branches/b", "PATCH /api/chats/c/messages/m",
                "POST /api/chats/c/messages/m/variants/1/select", "DELETE /api/chats/c/messages/m/variants/1",
                "POST /api/chats/c/messages/m/branch",
            ),
            seen,
        )
        assertEquals("T", body(0)["title"]!!.jsonPrimitive.content)
        assertEquals(2, body(2)["greetingIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals("L", body(4)["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateCharacterSendsExplicitNullToClearAField() = runBlocking<Unit> {
        engine.respond(200, Fixtures.text("character_patch.json"))
        val result = local.updateCharacter("c", patchOf("name" to "Aria the Wise", "creatorNotes" to null, "tags" to listOf("a", "b")))
        assertEquals("Aria the Wise", result.character!!.name)
        val sent = body()
        assertTrue(sent.containsKey("creatorNotes"))
        assertEquals("null", sent["creatorNotes"]!!.toString())
        assertEquals(2, sent["tags"]!!.jsonArray.size)
    }

    @Test
    fun sendBodyIncludesAttachmentsOnlyWhenPresent() = runBlocking<Unit> {
        engine.lines = emptyList()
        local.sendMessageStream("c", "hello").toList()
        local.sendMessageStream(
            "c", "look",
            listOf(Attachment(id = "a1", assetId = "asset_1", type = "image", name = "x.png", mimeType = "image/png", sizeBytes = 10)),
        ).toList()
        assertTrue(!body(0).containsKey("attachments"))
        val att = body(1)["attachments"]!!.jsonArray.single().jsonObject
        assertEquals("asset_1", att["assetId"]!!.jsonPrimitive.content)
        assertEquals("image", att["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamRequestsAskForEventStreamWithLongTimeout() = runBlocking<Unit> {
        local.generateReplyStream("c").toList()
        val r = engine.requests.single()
        assertEquals("text/event-stream", r.headers["Accept"])
        assertEquals(HttpRequest.STREAM_READ_TIMEOUT_MS, r.readTimeoutMs)
        assertTrue(r.url.endsWith("/api/chats/c/generate-reply/stream"))
    }

    @Test
    fun streamDecodesCapturedGeneration() = runBlocking<Unit> {
        engine.lines = Fixtures.text("sse_send.txt").split("\n")
        val events = local.sendMessageStream("c", "Hello, Aria!").toList()
        assertEquals("*smiles* Hello, traveler. Welcome to the tower.", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.delta })
    }

    @Test
    fun importRequiresContent() {
        try { runBlocking { local.importCharacter("x.json") }; fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun importSendsMonolithAndJson() = runBlocking<Unit> {
        engine.respond(200, Fixtures.text("import_json.json"))
        val r = local.importCharacter("Bob.png", jsonText = "{}", monolithText = "---\nname: Bob\n---")
        assertEquals("character", r.imported!!.kind)
        assertEquals("Bob.png", body()["fileName"]!!.jsonPrimitive.content)
        assertTrue(body().containsKey("monolithText"))
    }

    @Test
    fun avatarUploadIsMultipartWithCropField() = runBlocking<Unit> {
        engine.respond(200, Fixtures.text("avatar_upload.json"))
        val r = local.uploadAvatar("c", byteArrayOf(1, 2, 3), "image/png", full = byteArrayOf(4))
        assertEquals("webp", r.avatarExt)
        val req = engine.requests.single()
        assertTrue(req.contentType!!.startsWith("multipart/form-data; boundary="))
        val text = req.body!!.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"crop\"") && text.contains("name=\"full\""))
    }

    @Test
    fun imageUrlsCarryTokenAndCacheBuster() {
        assertEquals(
            "http://192.168.1.5:8787/api/characters/c1/avatar?v=2026-11-01T00%3A13%3A17.988Z&token=t0k",
            remote.characterAvatarUrl("c1", "2026-11-01T00:13:17.988Z"),
        )
        assertEquals("http://127.0.0.1:8787/api/characters/c1/avatar/full", local.characterAvatarUrl("c1", full = true))
        assertEquals("http://192.168.1.5:8787/api/assets/asset_1", remote.assetUrl("asset_1"))
    }

    @Test
    fun connectionRejectsTrailingSlash() {
        try { ServerConnection("http://h:1/"); fail() } catch (_: IllegalArgumentException) {}
    }
}
