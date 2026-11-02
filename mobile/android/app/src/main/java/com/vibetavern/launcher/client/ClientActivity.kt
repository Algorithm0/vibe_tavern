package com.vibetavern.launcher.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vibetavern.client.model.CharacterListEntry
import com.vibetavern.launcher.MainActivity
import com.vibetavern.launcher.R
import com.vibetavern.launcher.client.platform.LocalServerState
import com.vibetavern.launcher.client.ui.CharacterEditorScreen
import com.vibetavern.launcher.client.ui.ChatListScreen
import com.vibetavern.launcher.client.ui.ChatScreen
import com.vibetavern.launcher.client.ui.ConnectScreen
import com.vibetavern.launcher.client.ui.LibraryScreen
import com.vibetavern.launcher.client.ui.VibeTavernTheme
import kotlinx.coroutines.launch

/** The native client. The old launcher screen ([MainActivity]) stays reachable from the connect screen. */
class ClientActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsOnce()
        setContent { VibeTavernTheme { ClientRoot(vm, openLauncher = { startActivity(Intent(this, MainActivity::class.java)) }) } }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { vm.localServer.refresh() }
    }

    /** The server's foreground notification is hidden without this on Android 13+; the server runs either way. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("vibe_tavern_client", MODE_PRIVATE)
        if (prefs.getBoolean("notifications_asked", false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val MAX_IMPORT_BYTES = 40 * 1024 * 1024

@Composable
private fun ClientRoot(vm: AppViewModel, openLauncher: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val stack by vm.stack.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val profiles by vm.profiles.state.collectAsStateWithLifecycle()
    val local by vm.localServer.state.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    val couldNotRead = stringResource(R.string.client_file_unreadable)
    val tooBig = stringResource(R.string.client_file_too_big)

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, m.actionLabel, duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    BackHandler(enabled = stack.size > 1) { vm.back() }

    // Import: any character file; the library decides by name/signature how to read it.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "character"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("no stream")
                name to bytes
            }.getOrNull()
            when {
                read == null -> vm.reportMessage(couldNotRead)
                read.second.size > MAX_IMPORT_BYTES -> vm.reportMessage(tooBig)
                else -> vm.importCharacter(read.first, read.second)
            }
        }
    }

    // Export: the person picks where to save; the PNG is fetched afterwards.
    var pendingExport by remember { mutableStateOf<CharacterListEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        val target = pendingExport
        pendingExport = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = vm.exportCharacterPng(target.id) ?: return@launch
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream") }.isSuccess
            if (!ok) vm.reportMessage(couldNotRead)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val s = session
        val screen = if (s == null) Screen.Connect else stack.last()
        when (screen) {
            Screen.Connect -> ConnectScreen(
                profiles = profiles, local = local, connection = connection,
                onConnect = vm::connect,
                onStartLocal = vm::startLocalServer,
                onStopLocal = vm::stopLocalServer,
                onAddServer = vm::addServer,
                onRemoveServer = vm::removeServer,
                onUpdateToken = { p, t -> vm.profiles.updateToken(p.id, t) },
                onCancelConnecting = vm::cancelConnecting,
                onDismissError = vm::dismissConnectionError,
                onOpenLauncher = openLauncher,
            )

            Screen.Library -> if (s != null) {
                val lib by s.library.state.collectAsStateWithLifecycle()
                LibraryScreen(
                    serverName = s.profile.name,
                    state = lib,
                    avatarUrl = { c -> if (c.hasAvatar) s.api.characterAvatarUrl(c.id, c.updatedAt.ifBlank { null }) else null },
                    onOpen = { vm.openCharacter(it.id) },
                    onNewCharacter = { vm.openEditor(null) },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onEdit = { vm.openEditor(it.id) },
                    onChats = { vm.push(Screen.Chats(it.id)) },
                    onNewChat = { vm.startNewChat(it.id) },
                    onDuplicate = { vm.duplicateCharacter(it.id) },
                    onArchive = { vm.archiveCharacter(it.id, it.name) },
                    onDelete = { vm.deleteCharacter(it.id) },
                    onExport = { pendingExport = it; exportLauncher.launch(it.name.ifBlank { "character" } + ".png") },
                    onActivateProvider = vm::activateProvider,
                    onOpenWebUi = { openWebUi(context, s.profile.baseUrl, s.profile.token) },
                    onSwitchServer = vm::disconnect,
                )
            }

            is Screen.Chats -> if (s != null) {
                val lib by s.library.state.collectAsStateWithLifecycle()
                ChatListScreen(
                    characterName = lib.characters.firstOrNull { it.id == screen.characterId }?.name.orEmpty(),
                    chats = lib.chatsOf(screen.characterId),
                    onBack = { vm.back() },
                    onOpen = { vm.openChat(it.id) },
                    onNewChat = { vm.startNewChat(screen.characterId) },
                    onDelete = { vm.deleteChat(it.id) },
                )
            }

            is Screen.Chat -> if (s != null) {
                val chatState by s.chat.state.collectAsStateWithLifecycle()
                val lib by s.library.state.collectAsStateWithLifecycle()
                val characterId = chatState.session.activeChat?.characterId?.takeIf { it.isNotBlank() } ?: chatState.session.character?.id
                val entry = lib.characters.firstOrNull { it.id == characterId }
                ChatScreen(
                    controller = s.chat,
                    state = chatState,
                    characterAvatarUrl = entry?.takeIf { it.hasAvatar }?.let { s.api.characterAvatarUrl(it.id, it.updatedAt.ifBlank { null }) },
                    onBack = { vm.back() },
                    onEditCharacter = { characterId?.let { vm.openEditor(it) } },
                    onAllChats = { characterId?.let { vm.push(Screen.Chats(it)) } },
                )
            }

            is Screen.Editor -> if (s != null) {
                val lib by s.library.state.collectAsStateWithLifecycle()
                val entry = screen.characterId?.let { id -> lib.characters.firstOrNull { it.id == id } }
                CharacterEditorScreen(
                    isNew = screen.characterId == null,
                    state = editor,
                    currentAvatarUrl = entry?.takeIf { it.hasAvatar }?.let { s.api.characterAvatarUrl(it.id, it.updatedAt.ifBlank { null }) },
                    saving = saving,
                    onBack = { vm.back() },
                    onSave = { draft, avatar -> vm.saveCharacter(screen.characterId, draft, avatar) },
                    onError = vm::reportMessage,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** The web UI stays the place for features the native client does not cover yet. */
private fun openWebUi(context: android.content.Context, baseUrl: String, token: String?) {
    val url = if (token != null) "$baseUrl/#token=${Uri.encode(token)}" else baseUrl
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
