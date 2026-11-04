package com.vibetavern.client.data

import com.vibetavern.client.model.CharacterListEntry
import com.vibetavern.client.model.ChatListItem
import com.vibetavern.client.model.ImportResult
import com.vibetavern.client.model.ProviderProfile
import com.vibetavern.client.model.SessionPatch
import com.vibetavern.client.model.SessionState
import com.vibetavern.client.network.ApiException
import com.vibetavern.client.network.PngCard
import com.vibetavern.client.network.TavernApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LibraryState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val characters: List<CharacterListEntry> = emptyList(),
    val chats: List<ChatListItem> = emptyList(),
    val providers: List<ProviderProfile> = emptyList(),
    /** The chat the server considers current; opened on launch. */
    val initialChatId: String? = null,
    /** The chat's full state from the same round trip, so opening it needs no second request. */
    val initialSession: SessionState? = null,
    val isFirstRun: Boolean = false,
    /** A fatal problem with the connection itself (not reachable, bad token). */
    val connectionError: ApiException? = null,
) {
    val activeProvider: ProviderProfile? get() = providers.firstOrNull { it.isActive }
    val hasUsableProvider: Boolean get() = activeProvider != null

    fun chatsOf(characterId: String): List<ChatListItem> =
        chats.filter { it.characterId == characterId }.sortedByDescending { it.lastMessageAt }
}

/** Outcome of a user action; carries a message fit to show, never raw exceptions. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val message: String, val exception: ApiException? = null) : Outcome<Nothing>
}

/**
 * The character library and chat list of one server.
 *
 * Everything the screens need before a chat opens comes from one `bootstrap` round trip,
 * and every mutation answers with the regions it changed, merged by [SessionState.merge].
 */
class LibraryController(private val api: TavernApi) {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var session = SessionState()

    suspend fun refresh(): Outcome<Unit> {
        _state.update { it.copy(loading = true) }
        return try {
            val boot = api.bootstrap()
            val providers = runCatchingApi { api.listProviders() } ?: _state.value.providers
            boot.snapshot?.let { session = session.merge(it) }
            _state.value = LibraryState(
                loaded = true,
                characters = boot.allCharacters.ifEmpty { boot.snapshot?.allCharacters.orEmpty() },
                chats = boot.snapshot?.chats ?: session.chats,
                providers = providers,
                initialChatId = boot.initialChatId,
                initialSession = boot.snapshot?.let { session },
                isFirstRun = boot.isFirstRun,
            )
            Outcome.Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, connectionError = e) }
            Outcome.Failed(ChatController.describe(e), e)
        }
    }

    // ── Characters ───────────────────────────────────────────────────────

    /** Creates a character; the server also starts its first chat, whose id is returned. */
    suspend fun createCharacter(draft: CharacterDraft): Outcome<ImportResult> =
        action { api.createCharacter(draft.toCreateFields()) }.also { applyImport(it) }

    suspend fun updateCharacter(characterId: String, draft: CharacterDraft, original: com.vibetavern.client.model.CharacterRecord): Outcome<SessionPatch> {
        val patch = draft.diff(original)
        if (patch.isEmpty()) return Outcome.Ok(SessionPatch(character = original))
        return action { api.updateCharacter(characterId, patch) }.also { outcome ->
            if (outcome is Outcome.Ok) outcome.value.character?.let { updated ->
                _state.update { s -> s.copy(characters = s.characters.map { if (it.id == updated.id) it.withCard(updated) else it }) }
            }
        }
    }

    suspend fun duplicateCharacter(characterId: String): Outcome<ImportResult> =
        action { api.duplicateCharacter(characterId) }.also { applyImport(it) }

    suspend fun deleteCharacter(characterId: String): Outcome<Unit> =
        action { api.deleteCharacter(characterId) }.also { outcome ->
            if (outcome is Outcome.Ok) _state.update { s ->
                // Deleting a character removes its chats on the server too.
                s.copy(
                    characters = s.characters.filterNot { it.id == characterId },
                    chats = s.chats.filterNot { it.characterId == characterId },
                )
            }
        }

    /** Hides a character. The server offers no list of archived ones, so callers should offer Undo. */
    suspend fun archiveCharacter(characterId: String): Outcome<Unit> =
        action { api.archiveCharacter(characterId); Unit }.also { outcome ->
            if (outcome is Outcome.Ok) _state.update { s ->
                s.copy(
                    characters = s.characters.filterNot { it.id == characterId },
                    chats = s.chats.filterNot { it.characterId == characterId },
                )
            }
        }

    suspend fun unarchiveCharacter(characterId: String): Outcome<Unit> =
        action { api.unarchiveCharacter(characterId); Unit }.also { if (it is Outcome.Ok) refresh() }

    /**
     * Imports a character card file: a SillyTavern PNG (V2/V3 chunks, or the lossless
     * native `vtmd` chunk), a card `.json`, or a native `.md`/`.vtmd` monolith.
     * For PNGs the picture also becomes the avatar.
     */
    suspend fun importCard(fileName: String, bytes: ByteArray): Outcome<ImportResult> {
        val lower = fileName.lowercase()
        val isPng = lower.endsWith(".png") || bytes.startsWithPngSignature()
        val isMonolith = lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".vtmd")

        val result = try {
            when {
                isPng -> {
                    val payload = PngCard.extract(bytes)
                    if (payload.isEmpty) return Outcome.Failed("This PNG has no character card inside.")
                    action { api.importCharacter(fileName, payload.jsonText, payload.monolithText) }
                }
                isMonolith -> action { api.importCharacter(fileName, monolithText = bytes.toString(Charsets.UTF_8)) }
                else -> action { api.importCharacter(fileName, jsonText = bytes.toString(Charsets.UTF_8)) }
            }
        } catch (e: PngCard.InvalidPngException) {
            return Outcome.Failed("This file is not a valid PNG image.")
        }

        if (result is Outcome.Ok) {
            applyImport(result)
            val characterId = result.value.characterId ?: result.value.snapshot?.character?.id
            if (isPng && characterId != null) {
                // The card imports fine without a picture, so an avatar failure is not an import failure.
                runCatchingApi { api.uploadAvatar(characterId, bytes, "image/png", full = bytes) }
                refresh()
            }
        }
        return result
    }

    suspend fun setAvatar(characterId: String, imageBytes: ByteArray, mimeType: String): Outcome<Unit> =
        action { api.uploadAvatar(characterId, imageBytes, mimeType, full = imageBytes); Unit }.also { if (it is Outcome.Ok) refresh() }

    /**
     * The full card for the editor. The server has no "get one character" call; the card
     * arrives with a chat of that character, so the newest existing chat is read, or a
     * first chat is started for a character that has none.
     */
    suspend fun loadCharacterCard(characterId: String): Outcome<com.vibetavern.client.model.CharacterRecord> = action {
        val existing = _state.value.chatsOf(characterId).firstOrNull()
        val chatId = existing?.id ?: when (val created = newChat(characterId)) {
            is Outcome.Ok -> created.value
            is Outcome.Failed -> error(created.message)
        }
        api.getChat(chatId).character?.takeIf { it.id == characterId }
            ?: error("The server did not return this character's card.")
    }

    // ── Chats ────────────────────────────────────────────────────────────

    /** Starts a new chat with [characterId] and returns its id. */
    suspend fun newChat(characterId: String): Outcome<String> =
        action {
            val patch = api.createChat(characterId)
            session = session.merge(patch)
            patch.chats?.let { chats -> _state.update { it.copy(chats = chats) } }
            patch.activeChat?.id ?: error("The server did not return the new chat")
        }

    suspend fun deleteChat(chatId: String): Outcome<Unit> =
        action { api.deleteChat(chatId) }.also { outcome ->
            if (outcome is Outcome.Ok) _state.update { s -> s.copy(chats = s.chats.filterNot { it.id == chatId }) }
        }

    suspend fun activateProvider(providerId: String): Outcome<Unit> =
        action { api.activateProvider(providerId); Unit }.also {
            if (it is Outcome.Ok) runCatchingApi { api.listProviders() }?.let { list -> _state.update { s -> s.copy(providers = list) } }
        }

    /** Keeps the library list in step with a chat the person is looking at (rename, new messages). */
    fun onChatListChanged(chats: List<ChatListItem>) {
        _state.update { it.copy(chats = chats) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun applyImport(outcome: Outcome<ImportResult>) {
        if (outcome !is Outcome.Ok) return
        val snap = outcome.value.snapshot ?: return
        session = session.merge(snap)
        _state.update { s ->
            s.copy(
                characters = snap.allCharacters ?: s.characters,
                chats = snap.chats ?: s.chats,
            )
        }
    }

    private fun CharacterListEntry.withCard(c: com.vibetavern.client.model.CharacterRecord) =
        copy(name = c.name, subtitle = c.subtitle, tags = c.tags, updatedAt = c.updatedAt)

    private suspend fun <T> action(block: suspend () -> T): Outcome<T> = try {
        Outcome.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        Outcome.Failed(ChatController.describe(e), e)
    } catch (e: IllegalStateException) {
        Outcome.Failed(e.message ?: "Something went wrong.")
    }

    private suspend fun <T> runCatchingApi(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiException) {
        null
    }

    private fun ByteArray.startsWithPngSignature(): Boolean =
        size >= 4 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
}
