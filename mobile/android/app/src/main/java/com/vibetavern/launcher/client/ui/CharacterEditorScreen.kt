package com.vibetavern.launcher.client.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibetavern.client.data.CharacterDraft
import com.vibetavern.client.model.CharacterRecord
import com.vibetavern.launcher.R
import com.vibetavern.launcher.client.EditorState

private const val MAX_AVATAR_BYTES = 12 * 1024 * 1024

@Composable
fun CharacterEditorScreen(
    isNew: Boolean,
    state: EditorState,
    currentAvatarUrl: String?,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (CharacterDraft, avatar: Pair<ByteArray, String>?) -> Unit,
    onError: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(if (isNew) R.string.client_new_character else R.string.client_edit_character), onBack = onBack)
        when (state) {
            is EditorState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is EditorState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorBanner(state.message)
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.client_back)) }
            }
            is EditorState.Ready -> EditorForm(state.card, currentAvatarUrl, saving, onBack, onSave, onError)
        }
    }
}

@Composable
private fun EditorForm(
    card: CharacterRecord?,
    currentAvatarUrl: String?,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (CharacterDraft, Pair<ByteArray, String>?) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(card) { mutableStateOf(card?.let(CharacterDraft::fromRecord) ?: CharacterDraft()) }
    var avatar by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val tooBig = stringResource(R.string.client_avatar_too_big)
    val unreadable = stringResource(R.string.client_file_unreadable)

    val dirty = avatar != null || (if (card == null) draft != CharacterDraft() else draft.hasChangesComparedTo(card))

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty")
            if (bytes.size > MAX_AVATAR_BYTES) onError(tooBig) else avatar = bytes to (context.contentResolver.getType(uri) ?: "image/png")
        } catch (_: Exception) {
            onError(unreadable)
        }
    }

    BackHandler(enabled = dirty && !saving) { confirmDiscard = true }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(avatar?.first ?: currentAvatarUrl, draft.name, 72.dp)
                OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.client_choose_avatar)) }
            }
            Field(R.string.client_f_name, draft.name, singleLine = true) { draft = draft.copy(name = it) }
            Field(R.string.client_f_description, draft.description, minLines = 4) { draft = draft.copy(description = it) }
            Field(R.string.client_f_personality, draft.personalitySummary, minLines = 2) { draft = draft.copy(personalitySummary = it) }
            Field(R.string.client_f_scenario, draft.scenario, minLines = 3) { draft = draft.copy(scenario = it) }
            Field(R.string.client_f_first_message, draft.firstMessage, minLines = 4) { draft = draft.copy(firstMessage = it) }

            Text(stringResource(R.string.client_f_alt_greetings), style = MaterialTheme.typography.titleSmall)
            draft.alternateGreetings.forEachIndexed { i, text ->
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = text, minLines = 2, modifier = Modifier.weight(1f),
                        onValueChange = { v -> draft = draft.copy(alternateGreetings = draft.alternateGreetings.toMutableList().also { it[i] = v }) },
                    )
                    TextButton(onClick = { draft = draft.copy(alternateGreetings = draft.alternateGreetings.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                }
            }
            TextButton(onClick = { draft = draft.copy(alternateGreetings = draft.alternateGreetings + "") }) { Text(stringResource(R.string.client_add_greeting)) }

            Field(R.string.client_f_tags, draft.tagsText, singleLine = true, supporting = R.string.client_f_tags_hint) { draft = draft.withTagsText(it) }

            var advanced by remember { mutableStateOf(false) }
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.client_hide_advanced else R.string.client_show_advanced)) }
            if (advanced) {
                Field(R.string.client_f_examples, draft.mesExample, minLines = 4) { draft = draft.copy(mesExample = it) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("always", "once", "depth", "disabled").forEach { mode ->
                        FilterChip(selected = draft.mesExampleMode == mode, onClick = { draft = draft.copy(mesExampleMode = mode) }, label = { Text(mode) })
                    }
                }
                Field(R.string.client_f_system_prompt, draft.systemPrompt, minLines = 3) { draft = draft.copy(systemPrompt = it) }
                Field(R.string.client_f_post_history, draft.postHistoryInstructions, minLines = 3) { draft = draft.copy(postHistoryInstructions = it) }
                Field(R.string.client_f_depth_prompt, draft.depthPrompt, minLines = 3) { draft = draft.copy(depthPrompt = it) }
                Field(R.string.client_f_creator_notes, draft.creatorNotes, minLines = 3) { draft = draft.copy(creatorNotes = it) }
            }
            Box(Modifier.padding(bottom = 8.dp))
        }
        Button(
            onClick = { onSave(draft, avatar) },
            enabled = draft.isValid && dirty && !saving,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        ) { Text(stringResource(R.string.client_save)) }
    }

    if (confirmDiscard) ConfirmDialog(
        title = stringResource(R.string.client_discard_title),
        text = stringResource(R.string.client_discard_text),
        confirmLabel = stringResource(R.string.client_discard),
        cancelLabel = stringResource(R.string.client_keep_editing),
        destructive = true,
        onConfirm = { confirmDiscard = false; onBack() },
        onDismiss = { confirmDiscard = false },
    )
}

@Composable
private fun Field(label: Int, value: String, minLines: Int = 1, singleLine: Boolean = false, supporting: Int? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}
