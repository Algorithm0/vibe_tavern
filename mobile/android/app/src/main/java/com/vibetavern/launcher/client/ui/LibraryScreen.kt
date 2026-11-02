package com.vibetavern.launcher.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibetavern.client.data.LibraryState
import com.vibetavern.client.model.CharacterListEntry
import com.vibetavern.launcher.R

@Composable
fun LibraryScreen(
    serverName: String,
    state: LibraryState,
    avatarUrl: (CharacterListEntry) -> String?,
    onOpen: (CharacterListEntry) -> Unit,
    onNewCharacter: () -> Unit,
    onImport: () -> Unit,
    onEdit: (CharacterListEntry) -> Unit,
    onChats: (CharacterListEntry) -> Unit,
    onNewChat: (CharacterListEntry) -> Unit,
    onDuplicate: (CharacterListEntry) -> Unit,
    onArchive: (CharacterListEntry) -> Unit,
    onDelete: (CharacterListEntry) -> Unit,
    onExport: (CharacterListEntry) -> Unit,
    onActivateProvider: (String) -> Unit,
    onOpenWebUi: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<CharacterListEntry?>(null) }
    val shown = remember(state.characters, query) {
        val q = query.trim()
        if (q.isEmpty()) state.characters
        else state.characters.filter { c -> c.name.contains(q, true) || c.tags.any { it.contains(q, true) } || c.subtitle.contains(q, true) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(serverName, onBack = null, actions = {
                TextButton(onClick = onSwitchServer) { Text(stringResource(R.string.client_switch_server)) }
            })
            if (state.loading && state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (!state.hasUsableProvider) ProviderBanner(state, onActivateProvider, onOpenWebUi, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.client_search_characters)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.characters.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.client_library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { c ->
                        CharacterRow(
                            c, avatarUrl(c), chatCount = state.chatsOf(c.id).size,
                            onOpen = { onOpen(c) }, onEdit = { onEdit(c) }, onChats = { onChats(c) }, onNewChat = { onNewChat(c) },
                            onDuplicate = { onDuplicate(c) }, onArchive = { onArchive(c) }, onDelete = { deleting = c }, onExport = { onExport(c) },
                        )
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = onImport) { Text("⇪", style = MaterialTheme.typography.titleMedium) }
            ExtendedFloatingActionButton(
                onClick = onNewCharacter,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.client_new_character)) },
            )
        }
    }

    deleting?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.client_delete_character_title, c.name),
            text = stringResource(R.string.client_delete_character_text),
            confirmLabel = stringResource(R.string.client_delete),
            cancelLabel = stringResource(R.string.client_cancel),
            destructive = true,
            onConfirm = { onDelete(c); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun CharacterRow(
    c: CharacterListEntry, avatar: String?, chatCount: Int,
    onOpen: () -> Unit, onEdit: () -> Unit, onChats: () -> Unit, onNewChat: () -> Unit,
    onDuplicate: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(avatar, c.name, 52.dp)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = c.subtitle.ifBlank { c.tags.joinToString(" · ") }
                if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chatCount > 0) Text(stringResource(R.string.client_chat_count, chatCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.client_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_new_chat)) }, onClick = { menu = false; onNewChat() })
                    if (chatCount > 0) DropdownMenuItem(text = { Text(stringResource(R.string.client_all_chats)) }, onClick = { menu = false; onChats() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_edit)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_export_png)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_archive)) }, onClick = { menu = false; onArchive() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** No active AI provider means nothing can be generated; say so and offer the fix we can do natively. */
@Composable
private fun ProviderBanner(state: LibraryState, onActivate: (String) -> Unit, onOpenWebUi: () -> Unit, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.client_no_provider_title), style = MaterialTheme.typography.titleSmall)
            if (state.providers.isEmpty()) {
                Text(stringResource(R.string.client_no_provider_text), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenWebUi) { Text(stringResource(R.string.client_open_web_ui)) }
            } else {
                Text(stringResource(R.string.client_pick_provider), style = MaterialTheme.typography.bodySmall)
                state.providers.forEach { p -> TextButton(onClick = { onActivate(p.id) }) { Text(p.name) } }
            }
        }
    }
}
