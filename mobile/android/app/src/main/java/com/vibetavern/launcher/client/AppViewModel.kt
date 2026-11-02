package com.vibetavern.launcher.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetavern.client.data.CharacterDraft
import com.vibetavern.client.data.Outcome
import com.vibetavern.client.data.ServerProfile
import com.vibetavern.client.data.ServerProfileRepository
import com.vibetavern.client.network.ApiException
import com.vibetavern.client.network.OkHttpEngine
import com.vibetavern.client.network.VibeTavernApi
import com.vibetavern.launcher.R
import com.vibetavern.launcher.client.platform.FileTextStore
import com.vibetavern.launcher.client.platform.KeystoreSecretCipher
import com.vibetavern.launcher.client.platform.LocalServerManager
import com.vibetavern.launcher.client.platform.LocalServerState
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The app's single source of truth: which server is connected, what screen is showing,
 * and the actions the screens trigger. Server state itself lives in [ServerSession].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val profiles = ServerProfileRepository(
        store = FileTextStore(File(app.filesDir, "server_profiles.json")),
        cipher = KeystoreSecretCipher(),
    )
    val localServer = LocalServerManager(app, viewModelScope)

    private val engine = OkHttpEngine()

    private val _session = MutableStateFlow<ServerSession?>(null)
    val session: StateFlow<ServerSession?> = _session.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Connect))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _editor = MutableStateFlow<EditorState>(EditorState.Loading)
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var connectJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            localServer.refresh()
            profiles.state.value.active?.let { connect(it) }
        }
    }

    private fun say(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, actionLabel, onAction))
    }

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    // ── Connection ───────────────────────────────────────────────────────

    fun connect(profile: ServerProfile) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            closeSession()
            _stack.value = listOf(Screen.Connect)
            _connection.value = ConnectionState.Connecting(profile)

            if (profile.isLocal) {
                localServer.start()
                val state = localServer.state.value
                if (state !is LocalServerState.Running) {
                    val message = (state as? LocalServerState.Failed)?.message ?: str(R.string.client_local_not_running)
                    _connection.value = ConnectionState.Failed(profile, message, unauthorized = false)
                    return@launch
                }
            }

            val api = VibeTavernApi(profile.connection(), engine)
            val session = ServerSession.create(profile, api)
            when (val result = session.library.refresh()) {
                is Outcome.Ok -> {
                    profiles.setActive(profile.id)
                    _session.value = session
                    _connection.value = ConnectionState.Idle
                    _stack.value = listOf(Screen.Library)
                    keepChatListInSync(session)
                }
                is Outcome.Failed -> {
                    session.close()
                    _connection.value = ConnectionState.Failed(profile, result.message, result.exception?.isUnauthorized == true)
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        closeSession()
        profiles.setActive(null)
        _connection.value = ConnectionState.Idle
        _stack.value = listOf(Screen.Connect)
    }

    fun cancelConnecting() {
        connectJob?.cancel()
        _connection.value = ConnectionState.Idle
    }

    fun dismissConnectionError() {
        if (_connection.value is ConnectionState.Failed) _connection.value = ConnectionState.Idle
    }

    private fun closeSession() {
        syncJob?.cancel()
        _session.value?.close()
        _session.value = null
    }

    /** Chat renames and new messages change the chat list; mirror them into the library. */
    private fun keepChatListInSync(session: ServerSession) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            session.chat.state.map { it.session.chats }.distinctUntilChanged().collect { chats ->
                if (chats.isNotEmpty()) session.library.onChatListChanged(chats)
            }
        }
    }

    fun addServer(address: String, token: String, name: String): Boolean =
        when (val r = profiles.addRemote(address, name.ifBlank { null }, token.ifBlank { null })) {
            is ServerProfileRepository.AddResult.InvalidAddress -> false
            is ServerProfileRepository.AddResult.Added -> { connect(r.profile); true }
            is ServerProfileRepository.AddResult.Updated -> { connect(r.profile); true }
        }

    fun removeServer(profile: ServerProfile) {
        if (_session.value?.profile?.id == profile.id) disconnect()
        profiles.remove(profile.id)
    }

    fun startLocalServer() { viewModelScope.launch { localServer.start() } }

    fun stopLocalServer() {
        if (_session.value?.profile?.isLocal == true) disconnect()
        localServer.stop()
    }

    // ── Navigation ───────────────────────────────────────────────────────

    fun push(screen: Screen) { _stack.value = _stack.value + screen }

    /** Returns false when there is nothing left to go back to (the activity should finish). */
    fun back(): Boolean {
        val stack = _stack.value
        if (stack.size <= 1) return false
        val leaving = stack.last()
        _stack.value = stack.dropLast(1)
        if (leaving is Screen.Chat || leaving is Screen.Chats) refreshLibrary()
        return true
    }

    private fun refreshLibrary() {
        val s = _session.value ?: return
        viewModelScope.launch { s.library.refresh() }
    }

    // ── Characters ───────────────────────────────────────────────────────

    fun openCharacter(characterId: String) {
        val s = _session.value ?: return
        val latest = s.library.state.value.chatsOf(characterId).firstOrNull()
        if (latest != null) openChat(latest.id) else startNewChat(characterId)
    }

    fun openChat(chatId: String) {
        val s = _session.value ?: return
        s.chat.open(chatId)
        val top = _stack.value.last()
        _stack.value = if (top is Screen.Chat) _stack.value.dropLast(1) + Screen.Chat(chatId) else _stack.value + Screen.Chat(chatId)
    }

    fun startNewChat(characterId: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            when (val r = s.library.newChat(characterId)) {
                is Outcome.Ok -> openChat(r.value)
                is Outcome.Failed -> say(r.message)
            }
        }
    }

    fun deleteChat(chatId: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            val r = s.library.deleteChat(chatId)
            if (r is Outcome.Failed) say(r.message)
        }
    }

    fun openEditor(characterId: String?) {
        val s = _session.value ?: return
        push(Screen.Editor(characterId))
        if (characterId == null) { _editor.value = EditorState.Ready(null); return }
        _editor.value = EditorState.Loading
        viewModelScope.launch {
            _editor.value = when (val r = s.library.loadCharacterCard(characterId)) {
                is Outcome.Ok -> EditorState.Ready(r.value)
                is Outcome.Failed -> EditorState.Failed(r.message)
            }
        }
    }

    fun saveCharacter(characterId: String?, draft: CharacterDraft, avatar: Pair<ByteArray, String>?) {
        val s = _session.value ?: return
        val original = (_editor.value as? EditorState.Ready)?.card
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
          try {
            val savedId: String? = if (characterId == null) {
                when (val r = s.library.createCharacter(draft)) {
                    is Outcome.Ok -> r.value.characterId ?: r.value.snapshot?.character?.id
                    is Outcome.Failed -> { say(r.message); return@launch }
                }
            } else {
                if (original == null) return@launch
                when (val r = s.library.updateCharacter(characterId, draft, original)) {
                    is Outcome.Ok -> characterId
                    is Outcome.Failed -> { say(r.message); return@launch }
                }
            }
            if (avatar != null && savedId != null) {
                val r = s.library.setAvatar(savedId, avatar.first, avatar.second)
                if (r is Outcome.Failed) say(r.message)
            }
            say(str(R.string.client_saved))
            back()
          } finally {
            _saving.value = false
          }
        }
    }

    fun duplicateCharacter(characterId: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            when (val r = s.library.duplicateCharacter(characterId)) {
                is Outcome.Ok -> say(str(R.string.client_duplicated))
                is Outcome.Failed -> say(r.message)
            }
        }
    }

    /** Archived characters cannot be listed again, so the snackbar's Undo is the only way back from the app. */
    fun archiveCharacter(characterId: String, name: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            when (val r = s.library.archiveCharacter(characterId)) {
                is Outcome.Ok -> say(str(R.string.client_archived, name), str(R.string.client_undo)) {
                    viewModelScope.launch {
                        val u = s.library.unarchiveCharacter(characterId)
                        if (u is Outcome.Failed) say(u.message)
                    }
                }
                is Outcome.Failed -> say(r.message)
            }
        }
    }

    fun deleteCharacter(characterId: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            val r = s.library.deleteCharacter(characterId)
            if (r is Outcome.Failed) say(r.message)
        }
    }

    fun importCharacter(fileName: String, bytes: ByteArray) {
        val s = _session.value ?: return
        viewModelScope.launch {
            when (val r = s.library.importCard(fileName, bytes)) {
                is Outcome.Ok -> {
                    val name = r.value.imported?.name?.takeIf { it.isNotBlank() } ?: fileName
                    val warnings = r.value.imported?.warningCount ?: 0
                    say(if (warnings > 0) str(R.string.client_imported_warnings, name, warnings) else str(R.string.client_imported, name))
                }
                is Outcome.Failed -> say(r.message)
            }
        }
    }

    /** PNG bytes of the character card, or null after showing the reason. */
    suspend fun exportCharacterPng(characterId: String): ByteArray? {
        val s = _session.value ?: return null
        return try {
            s.api.exportCharacterPng(characterId)
        } catch (e: ApiException) {
            say(com.vibetavern.client.data.ChatController.describe(e))
            null
        }
    }

    fun reportMessage(text: String) = say(text)

    fun activateProvider(providerId: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            val r = s.library.activateProvider(providerId)
            if (r is Outcome.Failed) say(r.message)
        }
    }

    override fun onCleared() {
        closeSession()
        super.onCleared()
    }
}
