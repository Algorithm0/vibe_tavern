package com.vibetavern.client.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Wire DTOs for the Vibe Tavern HTTP API (services/api).
 *
 * Source of truth: packages/api-contracts/src/wire-types.ts and
 * services/api/src/api/contract/session-types.ts, cross-checked against real
 * responses captured from a running server (see fixtures/README.md). The API
 * reference in docs/architecture/api-reference.md is partly stale, so these
 * shapes follow the code and the captured fixtures, not the markdown.
 *
 * Every property except identifiers has a default so a server-side addition,
 * removal or null never breaks decoding of an unrelated screen.
 */

// ─── Chats ───────────────────────────────────────────────────────────────

/** Sidebar row for one chat. */
@Serializable
data class ChatListItem(
    val id: String,
    val title: String = "",
    val characterId: String = "",
    val characterName: String = "",
    val subtitle: String = "",
    val activeBranchLabel: String = "",
    /** `rp` for roleplay chats; other values belong to Co-Author style chats. */
    val mode: String = "rp",
    val messageCount: Int = 0,
    val lastMessageAt: String = "",
    val updatedAt: String = "",
)

/** Active chat metadata. Config blobs we do not edit natively stay as raw JSON. */
@Serializable
data class ChatDto(
    val id: String,
    val characterId: String = "",
    val personaId: String? = null,
    val title: String = "",
    val summary: String = "",
    val messageHistoryLimit: Int = 0,
    val status: String = "active",
    val mode: String = "rp",
    val selectedGreetingIndex: Int = 0,
    val activeBranchId: String? = null,
    val promptPresetId: String? = null,
    val autoSummaryConfig: JsonElement? = null,
    val insightsConfig: JsonElement? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class ChatBranch(
    val id: String,
    val chatId: String = "",
    val parentBranchId: String? = null,
    val forkedFromMessageId: String? = null,
    val label: String = "",
    val createdAt: String = "",
)

@Serializable
data class SummaryDto(
    val id: String,
    val kind: String = "",
    val summary: String = "",
)

// ─── Messages ────────────────────────────────────────────────────────────

/** A file attached to a message (image, text file, audio, video). */
@Serializable
data class Attachment(
    val id: String,
    val assetId: String = "",
    /** `image` | `file` | `video` | `audio`. */
    val type: String = "file",
    val name: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val description: String? = null,
    val includeInPrompt: Boolean? = null,
    val durationMs: Long? = null,
    val tokenCount: Int? = null,
)

/** One swipe of a message. A message always has at least one variant. */
@Serializable
data class MessageVariant(
    val id: String,
    val messageId: String = "",
    val variantIndex: Int = 0,
    val content: String = "",
    val isSelected: Boolean = false,
    val finishReason: String? = null,
    /** Reasoning/"thinking" text produced by models that expose it. */
    val reasoning: String? = null,
    val reasoningDurationMs: Long? = null,
    val modelId: String? = null,
    val presetName: String? = null,
    val createdAt: String = "",
)

/**
 * A chat message. [content] mirrors the currently selected variant, so most UI
 * only needs [content]; [variants] and [selectedVariantIndex] drive swipes.
 */
@Serializable
data class MessageDto(
    val id: String,
    val chatId: String = "",
    val branchId: String = "",
    /** `user` | `assistant` | `system`. */
    val role: String = "assistant",
    val authorType: String = "",
    val position: Int = 0,
    val content: String = "",
    val modelId: String? = null,
    /** `complete` for settled messages. */
    val state: String = "complete",
    val createdAt: String = "",
    val updatedAt: String = "",
    val variants: List<MessageVariant> = emptyList(),
    val selectedVariantIndex: Int? = null,
    val attachments: List<Attachment>? = null,
) {
    val isUser: Boolean get() = role == "user"

    val selectedVariant: MessageVariant?
        get() = variants.firstOrNull { it.isSelected }
            ?: variants.firstOrNull { it.variantIndex == selectedVariantIndex }

    /** Reasoning text of the selected swipe, when the model produced any. */
    val reasoning: String? get() = selectedVariant?.reasoning?.takeIf { it.isNotBlank() }
}

// ─── Characters / personas ───────────────────────────────────────────────

/** Row in the character library. */
@Serializable
data class CharacterListEntry(
    val id: String,
    val name: String = "",
    val subtitle: String = "",
    val tags: List<String> = emptyList(),
    val avatarAssetId: String? = null,
    val avatarFullAssetId: String? = null,
    val avatarCropJson: String? = null,
    val avatarExt: String? = null,
    val avatarFullExt: String? = null,
    /** Bumped on every avatar upload; used as an image cache-buster. */
    val updatedAt: String = "",
) {
    /**
     * Avatars uploaded through `POST /api/characters/:id/avatar` set [avatarExt]
     * and leave [avatarAssetId] null (verified against a live server), so the
     * extension is the reliable signal. [avatarAssetId] only covers legacy data.
     */
    val hasAvatar: Boolean get() = avatarExt != null || avatarAssetId != null
}

/** Full character card. */
@Serializable
data class CharacterRecord(
    val id: String,
    val name: String = "",
    val description: String = "",
    val scenario: String = "",
    val systemPrompt: String = "",
    val personality: String? = null,
    val personalitySummary: String? = null,
    val firstMessage: String? = null,
    val mesExample: String? = null,
    /** `always` | `once` | `depth` | `disabled`. */
    val mesExampleMode: String = "always",
    val mesExampleDepth: Int = 4,
    val alternateGreetings: List<String> = emptyList(),
    val postHistoryInstructions: String? = null,
    val creatorNotes: String? = null,
    val depthPrompt: String? = null,
    val depthPromptDepth: Int? = null,
    val depthPromptRole: String? = null,
    val tags: List<String> = emptyList(),
    val subtitle: String = "",
    val avatarAssetId: String? = null,
    val avatarFullAssetId: String? = null,
    val avatarCropJson: String? = null,
    val avatarExt: String? = null,
    val avatarFullExt: String? = null,
    val avatarDescription: String? = null,
    val includeAvatarInPrompt: Boolean = false,
    val includeGalleryInPrompt: Boolean = false,
    val updatedAt: String = "",
)

@Serializable
data class PersonaRecord(
    val id: String,
    val name: String = "",
    val description: String = "",
    val pronouns: String? = null,
    val avatarAssetId: String? = null,
    val avatarExt: String? = null,
    val defaultForNewChats: Boolean = false,
    val updatedAt: String = "",
)

// ─── Providers / presets / runtime ───────────────────────────────────────

/**
 * Provider profile as sent to clients. The API key never crosses the wire;
 * only [hasStoredApiKey] does. Sampler fields are intentionally not modelled yet.
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val name: String = "",
    val providerPreset: String = "",
    val endpoint: String = "",
    val defaultModel: String? = null,
    val visionModel: String? = null,
    val hasStoredApiKey: Boolean = false,
    val isActive: Boolean = false,
)

@Serializable
data class PromptPresetSummary(
    val id: String,
    val name: String = "",
)

@Serializable
data class RuntimeVersion(val version: String = "")

@Serializable
data class MobileAccessInfo(
    val ips: List<String> = emptyList(),
    val port: Int = 8787,
    val token: String? = null,
    val tlsEnabled: Boolean = false,
)

/** Response of `POST /api/assets/upload`. */
@Serializable
data class UploadedAsset(
    val assetId: String,
    val url: String = "",
    /** Only present for text files. */
    val tokenCount: Int? = null,
)

/** Response of `POST /api/characters/:id/avatar`. */
@Serializable
data class AvatarUploadResult(
    val avatarExt: String? = null,
    val avatarFullExt: String? = null,
)

@Serializable
data class CharacterStatusResponse(
    val characterId: String = "",
    val status: String = "",
)
