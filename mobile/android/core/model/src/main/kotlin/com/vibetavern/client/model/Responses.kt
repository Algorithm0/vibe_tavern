package com.vibetavern.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One class for every "session" style response.
 *
 * The server returns the full `SessionSnapshot` from some endpoints (bootstrap,
 * import) and narrower subsets from most mutations (`MessageResponse`,
 * `VariantResponse`, `BranchResponse`, `ChatSwitchResponse`, `ChatCreateResponse`,
 * `ConfigPatchResponse`, `ChatListResponse`, ... in session-types.ts). A field
 * that is absent means "this call did not touch it", exactly like the web app's
 * `ingestSnapshot` (absent → preserve). So a single all-optional type models
 * all of them, and callers merge with [SessionState.merge].
 */
@Serializable
data class SessionPatch(
    val chats: List<ChatListItem>? = null,
    val allCharacters: List<CharacterListEntry>? = null,
    val activeChat: ChatDto? = null,
    val activeBranch: ChatBranch? = null,
    val branches: List<ChatBranch>? = null,
    val messages: List<MessageDto>? = null,
    val summaries: List<SummaryDto>? = null,
    val character: CharacterRecord? = null,
    val persona: PersonaRecord? = null,
)

@Serializable
data class BootstrapState(
    val initialChatId: String? = null,
    val snapshot: SessionPatch? = null,
    val isFirstRun: Boolean = false,
    val allCharacters: List<CharacterListEntry> = emptyList(),
    val promptPresets: List<PromptPresetSummary> = emptyList(),
    val uiSettings: JsonElement? = null,
    val isArmServer: Boolean = false,
)

@Serializable
data class ImportInfo(
    /** `character` | `lorebook` | `chat`. */
    val kind: String = "",
    val name: String = "",
    val fileName: String = "",
    val warningCount: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** Response of character create/import/duplicate. */
@Serializable
data class ImportResult(
    val activeChatId: String? = null,
    val snapshot: SessionPatch? = null,
    val characterId: String? = null,
    val imported: ImportInfo? = null,
)

/**
 * The client-side view of the session: what the server last told us about the
 * active chat. [merge] applies a [SessionPatch] with "absent → preserve" semantics.
 */
data class SessionState(
    val chats: List<ChatListItem> = emptyList(),
    val allCharacters: List<CharacterListEntry> = emptyList(),
    val activeChat: ChatDto? = null,
    val activeBranch: ChatBranch? = null,
    val branches: List<ChatBranch> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val summaries: List<SummaryDto> = emptyList(),
    val character: CharacterRecord? = null,
    val persona: PersonaRecord? = null,
) {
    fun merge(patch: SessionPatch): SessionState = copy(
        chats = patch.chats ?: chats,
        allCharacters = patch.allCharacters ?: allCharacters,
        activeChat = patch.activeChat ?: activeChat,
        activeBranch = patch.activeBranch ?: activeBranch,
        branches = patch.branches ?: branches,
        messages = patch.messages ?: messages,
        summaries = patch.summaries ?: summaries,
        character = patch.character ?: character,
        persona = patch.persona ?: persona,
    )
}
