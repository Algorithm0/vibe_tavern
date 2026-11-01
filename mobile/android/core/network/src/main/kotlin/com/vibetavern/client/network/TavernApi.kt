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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Everything the app asks of a Vibe Tavern server. [VibeTavernApi] is the HTTP
 * implementation; screens and controllers depend on this interface so they can be
 * tested with plain fakes.
 */
interface TavernApi {
    val connection: ServerConnection

    suspend fun runtimeVersion(): RuntimeVersion
    suspend fun bootstrap(): BootstrapState
    suspend fun mobileAccessInfo(): MobileAccessInfo

    suspend fun listProviders(): List<ProviderProfile>
    suspend fun activateProvider(providerId: String): ProviderProfile

    suspend fun getChat(chatId: String): SessionPatch
    suspend fun createChat(characterId: String?): SessionPatch
    suspend fun deleteChat(chatId: String)
    suspend fun renameChat(chatId: String, title: String): SessionPatch
    suspend fun clearChat(chatId: String): SessionPatch
    suspend fun forkChat(chatId: String, fromMessageId: String? = null): SessionPatch
    suspend fun setGreetingIndex(chatId: String, index: Int): SessionPatch

    suspend fun activateBranch(chatId: String, branchId: String): SessionPatch
    suspend fun deleteBranch(chatId: String, branchId: String): SessionPatch
    suspend fun renameBranch(chatId: String, branchId: String, label: String): SessionPatch
    suspend fun branchFromMessage(chatId: String, messageId: String): SessionPatch

    suspend fun appendMessage(chatId: String, content: String): SessionPatch
    fun sendMessageStream(chatId: String, content: String, attachments: List<Attachment> = emptyList()): Flow<StreamEvent>
    fun regenerateStream(chatId: String, messageId: String): Flow<StreamEvent>
    fun continueStream(chatId: String, messageId: String): Flow<StreamEvent>
    fun generateReplyStream(chatId: String): Flow<StreamEvent>
    suspend fun editMessage(chatId: String, messageId: String, content: String): SessionPatch
    suspend fun deleteMessage(chatId: String, messageId: String): SessionPatch
    suspend fun selectVariant(chatId: String, messageId: String, variantIndex: Int): SessionPatch
    suspend fun deleteVariant(chatId: String, messageId: String, variantIndex: Int): SessionPatch

    suspend fun createCharacter(fields: JsonObject): ImportResult
    suspend fun updateCharacter(characterId: String, patch: JsonObject): SessionPatch
    suspend fun deleteCharacter(characterId: String)
    suspend fun archiveCharacter(characterId: String): CharacterStatusResponse
    suspend fun unarchiveCharacter(characterId: String): CharacterStatusResponse
    suspend fun duplicateCharacter(characterId: String): ImportResult
    suspend fun uploadAvatar(characterId: String, crop: ByteArray, mimeType: String = "image/png", full: ByteArray? = null): AvatarUploadResult
    suspend fun exportCharacterPng(characterId: String): ByteArray
    suspend fun importCharacter(
        fileName: String,
        jsonText: String? = null,
        monolithText: String? = null,
        importEmbeddedBook: Boolean = false,
    ): ImportResult

    suspend fun uploadAsset(fileName: String, mimeType: String, bytes: ByteArray): UploadedAsset

    fun characterAvatarUrl(characterId: String, version: String? = null, full: Boolean = false): String
    fun assetUrl(assetId: String): String
}
