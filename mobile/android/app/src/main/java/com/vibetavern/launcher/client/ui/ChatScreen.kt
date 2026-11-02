package com.vibetavern.launcher.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.vibetavern.client.data.ChatController
import com.vibetavern.client.data.ChatUiState
import com.vibetavern.client.data.StreamingReply
import com.vibetavern.client.model.MessageDto
import com.vibetavern.launcher.R

private sealed interface Dialog {
    data class Edit(val message: MessageDto) : Dialog
    data class DeleteMessage(val message: MessageDto) : Dialog
    data object Rename : Dialog
    data object Clear : Dialog
    data object Branches : Dialog
    data object Greetings : Dialog
}

@Composable
fun ChatScreen(
    controller: ChatController,
    state: ChatUiState,
    characterAvatarUrl: String?,
    onBack: () -> Unit,
    onEditCharacter: () -> Unit,
    onAllChats: () -> Unit,
) {
    val session = state.session
    val characterName = session.character?.name.orEmpty()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    val messages = remember(session.messages, state.streaming) {
        // While a reply is being regenerated the old swipe stays hidden; the live bubble takes its place.
        val hide = state.streaming?.takeIf { it.kind == StreamingReply.Kind.REGENERATE || it.kind == StreamingReply.Kind.CONTINUE }?.targetMessageId
        session.messages.filter { it.id != hide }
    }
    val lastAssistantId = remember(session.messages) { session.messages.lastOrNull { !it.isUser && it.role != "system" }?.id }
    val lastIsUser = session.messages.lastOrNull()?.isUser == true

    val listState = rememberLazyListState()
    val itemCount = messages.size + if (state.streaming != null) 1 else 0
    val nearBottom by remember { derivedStateOf { val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= listState.layoutInfo.totalItemsCount - 2 } }
    LaunchedEffect(itemCount, state.chatId) { if (itemCount > 0) listState.scrollToItem(itemCount - 1) }
    LaunchedEffect(state.streaming?.text?.length, state.streaming?.reasoning?.length) {
        if (state.streaming != null && nearBottom && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(
            title = session.activeChat?.title?.takeIf { it.isNotBlank() } ?: characterName,
            onBack = onBack,
            leading = { Avatar(characterAvatarUrl, characterName, 34.dp, Modifier.padding(end = 8.dp)) },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.client_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.client_rename_chat)) }, onClick = { menu = false; dialog = Dialog.Rename })
                        DropdownMenuItem(text = { Text(stringResource(R.string.client_all_chats)) }, onClick = { menu = false; onAllChats() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.client_branches)) }, onClick = { menu = false; dialog = Dialog.Branches })
                        if (session.character?.alternateGreetings?.isNotEmpty() == true) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.client_greetings)) }, onClick = { menu = false; dialog = Dialog.Greetings })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.client_edit_character)) }, onClick = { menu = false; onEditCharacter() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.client_clear_chat), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; dialog = Dialog.Clear },
                        )
                    }
                }
            },
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                MessageBubble(
                    message = m,
                    characterName = characterName,
                    isLastAssistant = m.id == lastAssistantId,
                    enabled = !state.isGenerating,
                    onSwipe = { index -> controller.selectVariant(m.id, index) },
                    onRegenerate = { controller.regenerate(m.id) },
                    onContinue = { controller.continueMessage(m.id) },
                    onEdit = { dialog = Dialog.Edit(m) },
                    onDelete = { dialog = Dialog.DeleteMessage(m) },
                    onDeleteVariant = { index -> controller.deleteVariant(m.id, index) },
                    onBranch = { controller.branchFrom(m.id) },
                    onFork = { controller.forkFrom(m.id) },
                )
            }
            state.streaming?.let { live ->
                item(key = "streaming") {
                    val base = if (live.kind == StreamingReply.Kind.CONTINUE) session.messages.firstOrNull { it.id == live.targetMessageId }?.content.orEmpty() else ""
                    StreamingBubbles(live, base, characterName)
                }
            }
        }

        state.error?.let { ErrorBanner(it, onDismiss = controller::dismissError, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }

        Composer(
            generating = state.isGenerating,
            canGenerateReply = lastIsUser && !state.isGenerating,
            onSend = { controller.send(it) },
            onStop = controller::stop,
            onGenerateReply = controller::generateReply,
        )
    }

    when (val d = dialog) {
        is Dialog.Edit -> TextDialog(
            title = stringResource(R.string.client_edit_message), initial = d.message.content, multiline = true,
            onConfirm = { controller.editMessage(d.message.id, it); dialog = null }, onDismiss = { dialog = null },
        )
        is Dialog.DeleteMessage -> ConfirmDialog(
            title = stringResource(R.string.client_delete_message_title), text = stringResource(R.string.client_delete_message_text),
            confirmLabel = stringResource(R.string.client_delete), cancelLabel = stringResource(R.string.client_cancel), destructive = true,
            onConfirm = { controller.deleteMessage(d.message.id); dialog = null }, onDismiss = { dialog = null },
        )
        Dialog.Rename -> TextDialog(
            title = stringResource(R.string.client_rename_chat), initial = session.activeChat?.title.orEmpty(), multiline = false,
            onConfirm = { controller.renameChat(it); dialog = null }, onDismiss = { dialog = null },
        )
        Dialog.Clear -> ConfirmDialog(
            title = stringResource(R.string.client_clear_chat_title), text = stringResource(R.string.client_clear_chat_text),
            confirmLabel = stringResource(R.string.client_clear), cancelLabel = stringResource(R.string.client_cancel), destructive = true,
            onConfirm = { controller.clearChat(); dialog = null }, onDismiss = { dialog = null },
        )
        Dialog.Branches -> BranchesDialog(controller, state, onDismiss = { dialog = null })
        Dialog.Greetings -> GreetingsDialog(controller, state, onDismiss = { dialog = null })
        null -> Unit
    }
}

// ── Messages ─────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(
    message: MessageDto, characterName: String, isLastAssistant: Boolean, enabled: Boolean,
    onSwipe: (variantIndex: Int) -> Unit, onRegenerate: () -> Unit, onContinue: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onDeleteVariant: (variantIndex: Int) -> Unit,
    onBranch: () -> Unit, onFork: () -> Unit,
) {
    val isUser = message.isUser
    var menu by remember { mutableStateOf(false) }
    var showReasoning by remember { mutableStateOf(false) }
    val variants = message.variants
    val position = (variants.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: (message.selectedVariantIndex ?: 0)).coerceIn(0, maxOf(0, variants.size - 1))

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(
            if (isUser) stringResource(R.string.client_you) else characterName,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.reasoning?.let { reasoning ->
                    Text(
                        stringResource(if (showReasoning) R.string.client_hide_thoughts else R.string.client_show_thoughts),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showReasoning = !showReasoning },
                    )
                    if (showReasoning) Text(reasoning, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SelectionContainer { Text(rpAnnotated(message.content), style = MaterialTheme.typography.bodyLarge) }
                message.attachments?.takeIf { it.isNotEmpty() }?.let { list ->
                    Text(list.joinToString(" · ") { "📎 ${it.name}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isUser && variants.size > 1) {
                TextButton(onClick = { onSwipe(variants[position - 1].variantIndex) }, enabled = enabled && position > 0) { Text("◀") }
                Text("${position + 1}/${variants.size}", style = MaterialTheme.typography.labelMedium)
            }
            if (!isUser && isLastAssistant) {
                val atLastSwipe = variants.size <= 1 || position >= variants.size - 1
                TextButton(
                    onClick = { if (atLastSwipe) onRegenerate() else onSwipe(variants[position + 1].variantIndex) },
                    enabled = enabled,
                ) { Text(if (atLastSwipe) "↻" else "▶") }
                TextButton(onClick = onContinue, enabled = enabled) { Text(stringResource(R.string.client_continue)) }
            }
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled) { Text("⋯") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_edit)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_branch_here)) }, onClick = { menu = false; onBranch() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_fork_here)) }, onClick = { menu = false; onFork() })
                    if (!isUser && variants.size > 1) DropdownMenuItem(
                        text = { Text(stringResource(R.string.client_delete_swipe)) },
                        onClick = { menu = false; onDeleteVariant(variants[position].variantIndex) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.client_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun StreamingBubbles(live: StreamingReply, baseText: String, characterName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        live.pendingUserText?.let { text ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 340.dp)) {
                    Text(rpAnnotated(text), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Text(characterName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 340.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (live.reasoning.isNotBlank()) {
                        Text(
                            stringResource(if (live.reasoningDone) R.string.client_thoughts_done else R.string.client_thinking),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                        if (!live.reasoningDone) Text(live.reasoning.takeLast(400), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val shown = baseText + live.text
                    if (shown.isBlank() && live.reasoning.isBlank()) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                    else if (shown.isNotBlank()) Text(rpAnnotated(shown), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ── Composer ─────────────────────────────────────────────────────────────

@Composable
private fun Composer(generating: Boolean, canGenerateReply: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onGenerateReply: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (canGenerateReply) OutlinedButton(onClick = onGenerateReply, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.client_generate_reply)) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.client_message_hint)) },
                maxLines = 6, modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
            )
            if (generating) {
                Button(onClick = onStop, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.client_stop))
                }
            } else {
                Button(onClick = { onSend(text.trim()); text = "" }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.client_send))
                }
            }
        }
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun TextDialog(title: String, initial: String, multiline: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = !multiline, minLines = if (multiline) 5 else 1, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.client_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.client_cancel)) } },
    )
}

@Composable
private fun BranchesDialog(controller: ChatController, state: ChatUiState, onDismiss: () -> Unit) {
    val branches = state.session.branches
    val active = state.session.activeBranch?.id ?: state.session.activeChat?.activeBranchId
    var renaming by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.client_branches)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (branches.isEmpty()) Text(stringResource(R.string.client_no_branches), color = MaterialTheme.colorScheme.onSurfaceVariant)
                branches.forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (b.id == active) "● " else "○ ") + b.label.ifBlank { stringResource(R.string.client_main_branch) },
                            modifier = Modifier.weight(1f).clickable(enabled = b.id != active && !state.isGenerating) { controller.activateBranch(b.id); onDismiss() },
                        )
                        TextButton(onClick = { renaming = b.id }) { Text("✎") }
                        if (b.id != active && b.parentBranchId != null) TextButton(onClick = { controller.deleteBranch(b.id) }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.client_close)) } },
    )
    renaming?.let { id ->
        TextDialog(
            title = stringResource(R.string.client_rename_branch), initial = branches.firstOrNull { it.id == id }?.label.orEmpty(), multiline = false,
            onConfirm = { controller.renameBranch(id, it); renaming = null }, onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun GreetingsDialog(controller: ChatController, state: ChatUiState, onDismiss: () -> Unit) {
    val card = state.session.character ?: return
    val selected = state.session.activeChat?.selectedGreetingIndex ?: 0
    val options = listOf(card.firstMessage.orEmpty()) + card.alternateGreetings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.client_greetings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, greeting ->
                    Surface(
                        color = if (i == selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isGenerating) { controller.setGreeting(i); onDismiss() },
                    ) { Text(greeting.take(220).ifBlank { "—" }, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 4) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.client_close)) } },
    )
}
