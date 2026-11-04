package com.vibetavern.client.data

import com.vibetavern.client.model.BootstrapState
import com.vibetavern.client.model.CharacterListEntry
import com.vibetavern.client.model.CharacterRecord
import com.vibetavern.client.model.CharacterStatusResponse
import com.vibetavern.client.model.ChatDto
import com.vibetavern.client.model.ChatListItem
import com.vibetavern.client.model.ImportInfo
import com.vibetavern.client.model.ImportResult
import com.vibetavern.client.model.ProviderProfile
import com.vibetavern.client.model.SessionPatch
import com.vibetavern.client.network.ApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryControllerTest {

    private val api = FakeTavernApi()
    private val library = LibraryController(api)

    private val aria = CharacterListEntry(id = "c1", name = "Aria")
    private val bob = CharacterListEntry(id = "c2", name = "Bob", avatarExt = "webp")
    private val chatAria = ChatListItem(id = "chat1", characterId = "c1", characterName = "Aria", lastMessageAt = "2026-11-01T10:00:00Z")
    private val chatAria2 = ChatListItem(id = "chat2", characterId = "c1", characterName = "Aria", lastMessageAt = "2026-11-01T12:00:00Z")
    private val chatBob = ChatListItem(id = "chat3", characterId = "c2", characterName = "Bob")
    private val provider = ProviderProfile(id = "p1", name = "Mock", isActive = true)

    private fun bootstrap(withSnapshot: Boolean = true) = BootstrapState(
        initialChatId = if (withSnapshot) "chat1" else null,
        snapshot = if (withSnapshot) SessionPatch(chats = listOf(chatAria, chatAria2, chatBob), activeChat = ChatDto(id = "chat1")) else null,
        isFirstRun = !withSnapshot,
        allCharacters = listOf(aria, bob),
    )

    private fun png(): ByteArray {
        // Minimal PNG with a base64 `chara` tEXt chunk.
        val card = java.util.Base64.getEncoder().encodeToString("""{"spec":"chara_card_v2","data":{"name":"Eve"}}""".toByteArray())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
            val body = type.toByteArray() + data
            out.write(body)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(java.util.zip.CRC32().apply { update(body) }.value.toInt()).array())
            return out.toByteArray()
        }
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ByteArray(13)) + chunk("tEXt", "chara".toByteArray() + 0 + card.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun refreshLoadsCharactersChatsProvidersAndInitialChat() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }
        api.onListProviders = { listOf(provider) }
        assertTrue(library.refresh() is Outcome.Ok)
        val s = library.state.value
        assertTrue(s.loaded)
        assertEquals(listOf("Aria", "Bob"), s.characters.map { it.name })
        assertEquals("chat1", s.initialChatId)
        assertNotNull(s.initialSession)
        assertEquals(provider, s.activeProvider)
        assertEquals(listOf("chat2", "chat1"), s.chatsOf("c1").map { it.id })      // newest first
        assertEquals(listOf("chat3"), s.chatsOf("c2").map { it.id })
    }

    @Test
    fun firstRunHasNoChatAndNoProvider() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap(withSnapshot = false) }
        library.refresh()
        val s = library.state.value
        assertTrue(s.isFirstRun)
        assertNull(s.initialChatId)
        assertNull(s.initialSession)
        assertFalse(s.hasUsableProvider)
    }

    @Test
    fun unreachableServerSurfacesAConnectionError() = runBlocking<Unit> {
        api.onBootstrap = { throw ApiException.network(java.io.IOException("refused")) }
        val r = library.refresh()
        assertTrue(r is Outcome.Failed)
        assertTrue(library.state.value.connectionError!!.isNetwork)
        assertFalse(library.state.value.loading)
    }

    @Test
    fun aProviderListFailureDoesNotBreakTheLibrary() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }
        api.onListProviders = { throw ApiException(500, "Internal", "boom") }
        assertTrue(library.refresh() is Outcome.Ok)
        assertEquals(2, library.state.value.characters.size)
    }

    @Test
    fun createCharacterReturnsTheFirstChatAndUpdatesTheLists() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        val eve = CharacterListEntry(id = "c3", name = "Eve")
        val newChat = ChatListItem(id = "chat4", characterId = "c3", characterName = "Eve")
        var sent: String? = null
        api.onCreateCharacter = { fields ->
            sent = fields["name"].toString()
            ImportResult(activeChatId = "chat4", snapshot = SessionPatch(allCharacters = listOf(aria, bob, eve), chats = listOf(chatAria, chatBob, newChat)))
        }
        val r = library.createCharacter(CharacterDraft(name = "Eve")) as Outcome.Ok
        assertEquals("chat4", r.value.activeChatId)
        assertEquals("\"Eve\"", sent)
        assertEquals(3, library.state.value.characters.size)
        assertTrue(library.state.value.chats.any { it.id == "chat4" })
    }

    @Test
    fun updateCharacterWithNoChangesDoesNotCallTheServer() = runBlocking<Unit> {
        val card = CharacterRecord(id = "c1", name = "Aria")
        val r = library.updateCharacter("c1", CharacterDraft.fromRecord(card), card)
        assertTrue(r is Outcome.Ok)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun updateCharacterSendsTheDiffAndRefreshesTheListEntry() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        val card = CharacterRecord(id = "c1", name = "Aria")
        var sentKeys: Set<String>? = null
        api.onUpdateCharacter = { _, patch -> sentKeys = patch.keys; SessionPatch(character = card.copy(name = "Aria the Wise", updatedAt = "now")) }
        library.updateCharacter("c1", CharacterDraft.fromRecord(card).copy(name = "Aria the Wise"), card)
        assertEquals(setOf("name"), sentKeys)
        assertEquals("Aria the Wise", library.state.value.characters.first { it.id == "c1" }.name)
    }

    @Test
    fun deletingACharacterAlsoDropsItsChats() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onDeleteCharacter = { }
        assertTrue(library.deleteCharacter("c1") is Outcome.Ok)
        val s = library.state.value
        assertEquals(listOf("Bob"), s.characters.map { it.name })
        assertEquals(listOf("chat3"), s.chats.map { it.id })
    }

    @Test
    fun archiveHidesLocallyAndUnarchiveRestoresViaRefresh() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onArchive = { CharacterStatusResponse(it, "archived") }
        library.archiveCharacter("c1")
        assertEquals(listOf("Bob"), library.state.value.characters.map { it.name })
        api.onUnarchive = { CharacterStatusResponse(it, "active") }
        library.unarchiveCharacter("c1")
        assertEquals(2, library.state.value.characters.size)             // came back from the server
    }

    @Test
    fun importPngSendsTheCardThenUploadsThePictureAsAvatar() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        var json: String? = null
        api.onImport = { _, j, _ -> json = j; ImportResult(characterId = "c3", activeChatId = "chat4", imported = ImportInfo(kind = "character", name = "Eve")) }
        val r = library.importCard("Eve.png", png())
        assertTrue(r is Outcome.Ok)
        assertTrue(json!!.contains("\"Eve\""))
        assertTrue(api.calls.contains("avatar:c3"))
    }

    @Test
    fun anAvatarFailureDoesNotFailTheImport() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onImport = { _, _, _ -> ImportResult(characterId = "c3", activeChatId = "chat4") }
        api.onUploadAvatar = { _, _ -> throw ApiException(413, "Validation", "too large") }
        assertTrue(library.importCard("Eve.png", png()) is Outcome.Ok)
    }

    @Test
    fun importPngWithoutACardOrNotAPngGivesClearMessages() = runBlocking<Unit> {
        val noCard = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("This PNG has no character card inside.", (library.importCard("x.png", noCard) as Outcome.Failed).message)
        assertEquals("This file is not a valid PNG image.", (library.importCard("x.png", "hello".toByteArray()) as Outcome.Failed).message)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun importRoutesJsonAndMonolithFilesToTheRightFields() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        val seen = mutableListOf<Pair<String?, String?>>()
        api.onImport = { _, j, m -> seen += j to m; ImportResult() }
        library.importCard("card.json", """{"name":"A"}""".toByteArray())
        library.importCard("card.md", "---\nname: B\n---".toByteArray())
        library.importCard("card.vtmd", "---\nname: C\n---".toByteArray())
        assertEquals("""{"name":"A"}""" to null, seen[0])
        assertEquals(null to "---\nname: B\n---", seen[1])
        assertNull(seen[2].first)
    }

    @Test
    fun serverRejectionsComeBackAsFailedOutcomesWithTheServersMessage() = runBlocking<Unit> {
        api.onCreateCharacter = { throw ApiException(400, "Validation", "name: Too small") }
        val r = library.createCharacter(CharacterDraft(name = "X")) as Outcome.Failed
        assertEquals("name: Too small", r.message)
    }

    @Test
    fun newChatReturnsItsIdAndUpdatesTheList() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        val fresh = ChatListItem(id = "chat9", characterId = "c2", characterName = "Bob")
        api.onCreateChat = { SessionPatch(chats = listOf(chatAria, chatBob, fresh), activeChat = ChatDto(id = "chat9")) }
        assertEquals("chat9", (library.newChat("c2") as Outcome.Ok).value)
        assertTrue(library.state.value.chats.any { it.id == "chat9" })
    }

    @Test
    fun newChatWithoutAnIdInTheResponseFailsCleanly() = runBlocking<Unit> {
        api.onCreateChat = { SessionPatch() }
        assertTrue(library.newChat("c2") is Outcome.Failed)
    }

    @Test
    fun deleteChatRemovesOnlyThatChat() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onDeleteChat = { }
        library.deleteChat("chat2")
        assertEquals(listOf("chat1", "chat3"), library.state.value.chats.map { it.id })
    }

    @Test
    fun activatingAProviderRefreshesTheList() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }
        api.onListProviders = { listOf(provider.copy(isActive = false)) }
        library.refresh()
        assertFalse(library.state.value.hasUsableProvider)
        api.onActivateProvider = { provider }
        api.onListProviders = { listOf(provider) }
        library.activateProvider("p1")
        assertTrue(library.state.value.hasUsableProvider)
    }

    @Test
    fun loadCharacterCardReadsTheNewestExistingChat() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onGetChat = { id -> SessionPatch(character = CharacterRecord(id = "c1", name = "Aria"), activeChat = ChatDto(id = id)) }
        val r = library.loadCharacterCard("c1") as Outcome.Ok
        assertEquals("Aria", r.value.name)
        assertEquals(listOf("getChat:chat2"), api.calls.filter { it.startsWith("getChat") })   // chat2 is newest
    }

    @Test
    fun loadCharacterCardStartsAChatForACharacterWithoutOne() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onCreateChat = { SessionPatch(chats = listOf(chatAria, chatBob, ChatListItem(id = "n1", characterId = "c9")), activeChat = ChatDto(id = "n1")) }
        api.onGetChat = { SessionPatch(character = CharacterRecord(id = "c9", name = "Zed")) }
        assertEquals("Zed", (library.loadCharacterCard("c9") as Outcome.Ok).value.name)
    }

    @Test
    fun loadCharacterCardFailsCleanlyWhenTheCardIsMissing() = runBlocking<Unit> {
        api.onBootstrap = { bootstrap() }; library.refresh()
        api.onGetChat = { SessionPatch() }
        assertTrue(library.loadCharacterCard("c1") is Outcome.Failed)
    }
}
