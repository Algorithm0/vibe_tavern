package com.vibetavern.launcher.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.vibetavern.client.model.ChatListItem
import com.vibetavern.launcher.R

@Composable
fun ChatListScreen(
    characterName: String,
    chats: List<ChatListItem>,
    onBack: () -> Unit,
    onOpen: (ChatListItem) -> Unit,
    onNewChat: () -> Unit,
    onDelete: (ChatListItem) -> Unit,
) {
    var deleting by remember { mutableStateOf<ChatListItem?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(characterName, onBack = onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chats, key = { it.id }) { chat ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpen(chat) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(chat.title.ifBlank { stringResource(R.string.client_untitled_chat) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                stringResource(R.string.client_message_count, chat.messageCount),
                                chat.activeBranchLabel.takeIf { it.isNotBlank() },
                                chat.lastMessageAt.take(10).takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { deleting = chat }) { Text(stringResource(R.string.client_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Text(stringResource(R.string.client_new_chat))
        }
    }
    deleting?.let { chat ->
        ConfirmDialog(
            title = stringResource(R.string.client_delete_chat_title),
            text = stringResource(R.string.client_delete_chat_text),
            confirmLabel = stringResource(R.string.client_delete),
            cancelLabel = stringResource(R.string.client_cancel),
            destructive = true,
            onConfirm = { onDelete(chat); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}
