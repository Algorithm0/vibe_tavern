package com.vibetavern.client.data

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
import com.vibetavern.client.network.ServerConnection
import com.vibetavern.client.network.StreamEvent
import com.vibetavern.client.network.TavernApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

/** Scriptable [TavernApi]: set the lambdas a test needs; anything else fails loudly. */
class FakeTavernApi : TavernApi {
    override val connection = ServerConnection("http://127.0.0.1:8787")

    val calls = mutableListOf<String>()
    private fun <T> unexpected(name: String): T = throw NotImplementedError("FakeTavernApi.$name not scripted")

    var onGetChat: suspend (String) -> SessionPatch = { unexpected("getChat") }
    var onSend: (String, String, List<Attachment>) -> Flow<StreamEvent> = { _, _, _ -> emptyFlow() }
    var onRegenerate: (String, String) -> Flow<StreamEvent> = { _, _ -> emptyFlow() }
    var onContinue: (String, String) -> Flow<StreamEvent> = { _, _ -> emptyFlow() }
    var onGenerateReply: (String) -> Flow<StreamEvent> = { emptyFlow() }
    var onSelectVariant: suspend (String, String, Int) -> SessionPatch = { _, _, _ -> unexpected("selectVariant") }
    var onEdit: suspend (String, String, String) -> SessionPatch = { _, _, _ -> unexpected("editMessage") }
    var onDeleteMessage: suspend (String, String) -> SessionPatch = { _, _ -> unexpected("deleteMessage") }
    var onRename: suspend (String, String) -> SessionPatch = { _, _ -> unexpected("renameChat") }

    var onBootstrap: suspend () -> BootstrapState = { unexpected("bootstrap") }
    var onListProviders: suspend () -> List<ProviderProfile> = { emptyList() }
    var onCreateCharacter: suspend (JsonObject) -> ImportResult = { unexpected("createCharacter") }
    var onUpdateCharacter: suspend (String, JsonObject) -> SessionPatch = { _, _ -> unexpected("updateCharacter") }
    var onDeleteCharacter: suspend (String) -> Unit = { unexpected("deleteCharacter") }
    var onArchive: suspend (String) -> CharacterStatusResponse = { unexpected("archiveCharacter") }
    var onUnarchive: suspend (String) -> CharacterStatusResponse = { unexpected("unarchiveCharacter") }
    var onDuplicate: suspend (String) -> ImportResult = { unexpected("duplicateCharacter") }
    var onImport: suspend (String, String?, String?) -> ImportResult = { _, _, _ -> unexpected("importCharacter") }
    var onUploadAvatar: suspend (String, ByteArray) -> AvatarUploadResult = { _, _ -> AvatarUploadResult("webp", "png") }
    var onCreateChat: suspend (String?) -> SessionPatch = { unexpected("createChat") }
    var onDeleteChat: suspend (String) -> Unit = { unexpected("deleteChat") }
    var onActivateProvider: suspend (String) -> ProviderProfile = { unexpected("activateProvider") }

    override suspend fun runtimeVersion() = RuntimeVersion("test")
    override suspend fun bootstrap() = onBootstrap()
    override suspend fun mobileAccessInfo() = MobileAccessInfo()
    override suspend fun listProviders() = onListProviders()
    override suspend fun activateProvider(providerId: String) = onActivateProvider(providerId)

    override suspend fun getChat(chatId: String): SessionPatch { calls += "getChat:$chatId"; return onGetChat(chatId) }
    override suspend fun createChat(characterId: String?) = onCreateChat(characterId)
    override suspend fun deleteChat(chatId: String) = onDeleteChat(chatId)
    override suspend fun renameChat(chatId: String, title: String) = onRename(chatId, title)
    override suspend fun clearChat(chatId: String): SessionPatch = unexpected("clearChat")
    override suspend fun forkChat(chatId: String, fromMessageId: String?): SessionPatch = unexpected("forkChat")
    override suspend fun setGreetingIndex(chatId: String, index: Int): SessionPatch = unexpected("setGreetingIndex")
    override suspend fun activateBranch(chatId: String, branchId: String): SessionPatch = unexpected("activateBranch")
    override suspend fun deleteBranch(chatId: String, branchId: String): SessionPatch = unexpected("deleteBranch")
    override suspend fun renameBranch(chatId: String, branchId: String, label: String): SessionPatch = unexpected("renameBranch")
    override suspend fun branchFromMessage(chatId: String, messageId: String): SessionPatch = unexpected("branchFromMessage")
    override suspend fun appendMessage(chatId: String, content: String): SessionPatch = unexpected("appendMessage")

    override fun sendMessageStream(chatId: String, content: String, attachments: List<Attachment>): Flow<StreamEvent> {
        calls += "send:$chatId:$content"; return onSend(chatId, content, attachments)
    }
    override fun regenerateStream(chatId: String, messageId: String): Flow<StreamEvent> {
        calls += "regenerate:$messageId"; return onRegenerate(chatId, messageId)
    }
    override fun continueStream(chatId: String, messageId: String): Flow<StreamEvent> {
        calls += "continue:$messageId"; return onContinue(chatId, messageId)
    }
    override fun generateReplyStream(chatId: String): Flow<StreamEvent> {
        calls += "reply"; return onGenerateReply(chatId)
    }
    override suspend fun editMessage(chatId: String, messageId: String, content: String) = onEdit(chatId, messageId, content)
    override suspend fun deleteMessage(chatId: String, messageId: String) = onDeleteMessage(chatId, messageId)
    override suspend fun selectVariant(chatId: String, messageId: String, variantIndex: Int) = onSelectVariant(chatId, messageId, variantIndex)
    override suspend fun deleteVariant(chatId: String, messageId: String, variantIndex: Int): SessionPatch = unexpected("deleteVariant")

    override suspend fun createCharacter(fields: JsonObject) = onCreateCharacter(fields)
    override suspend fun updateCharacter(characterId: String, patch: JsonObject) = onUpdateCharacter(characterId, patch)
    override suspend fun deleteCharacter(characterId: String) = onDeleteCharacter(characterId)
    override suspend fun archiveCharacter(characterId: String) = onArchive(characterId)
    override suspend fun unarchiveCharacter(characterId: String) = onUnarchive(characterId)
    override suspend fun duplicateCharacter(characterId: String) = onDuplicate(characterId)
    override suspend fun uploadAvatar(characterId: String, crop: ByteArray, mimeType: String, full: ByteArray?): AvatarUploadResult {
        calls += "avatar:$characterId"; return onUploadAvatar(characterId, crop)
    }
    override suspend fun exportCharacterPng(characterId: String): ByteArray = unexpected("exportCharacterPng")
    override suspend fun importCharacter(fileName: String, jsonText: String?, monolithText: String?, importEmbeddedBook: Boolean): ImportResult {
        calls += "import:$fileName"; return onImport(fileName, jsonText, monolithText)
    }
    override suspend fun uploadAsset(fileName: String, mimeType: String, bytes: ByteArray): UploadedAsset = unexpected("uploadAsset")

    override fun characterAvatarUrl(characterId: String, version: String?, full: Boolean) = "http://127.0.0.1:8787/api/characters/$characterId/avatar"
    override fun assetUrl(assetId: String) = "http://127.0.0.1:8787/api/assets/$assetId"
}
