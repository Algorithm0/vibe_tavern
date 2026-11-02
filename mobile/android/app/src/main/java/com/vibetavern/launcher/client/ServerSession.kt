package com.vibetavern.launcher.client

import com.vibetavern.client.data.ChatController
import com.vibetavern.client.data.LibraryController
import com.vibetavern.client.data.ServerProfile
import com.vibetavern.client.network.TavernApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Everything tied to one connected server. Dropping it (disconnect, switching servers) cancels all its work. */
class ServerSession private constructor(
    val profile: ServerProfile,
    val api: TavernApi,
    val scope: CoroutineScope,
) {
    val library = LibraryController(api)
    val chat = ChatController(api, scope)

    fun close() = scope.cancel()

    companion object {
        fun create(profile: ServerProfile, api: TavernApi) =
            ServerSession(profile, api, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}

sealed interface Screen {
    data object Connect : Screen
    data object Library : Screen
    data class Chats(val characterId: String) : Screen
    data class Chat(val chatId: String) : Screen
    /** [characterId] null means a new character. */
    data class Editor(val characterId: String?) : Screen
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val profile: ServerProfile) : ConnectionState
    data class Failed(val profile: ServerProfile, val message: String, val unauthorized: Boolean) : ConnectionState
}

sealed interface EditorState {
    data object Loading : EditorState
    /** [card] is null when creating a new character. */
    data class Ready(val card: com.vibetavern.client.model.CharacterRecord?) : EditorState
    data class Failed(val message: String) : EditorState
}

/** A one-off message for a snackbar, optionally with an action such as Undo. */
data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)
