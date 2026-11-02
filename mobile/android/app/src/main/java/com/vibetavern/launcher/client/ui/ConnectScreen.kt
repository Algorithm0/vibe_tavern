package com.vibetavern.launcher.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vibetavern.client.data.ProfilesState
import com.vibetavern.client.data.ServerProfile
import com.vibetavern.launcher.R
import com.vibetavern.launcher.client.ConnectionState
import com.vibetavern.launcher.client.platform.LocalServerState

@Composable
fun ConnectScreen(
    profiles: ProfilesState,
    local: LocalServerState,
    connection: ConnectionState,
    onConnect: (ServerProfile) -> Unit,
    onStartLocal: () -> Unit,
    onStopLocal: () -> Unit,
    onAddServer: (address: String, token: String, name: String) -> Boolean,
    onRemoveServer: (ServerProfile) -> Unit,
    onUpdateToken: (ServerProfile, String) -> Unit,
    onCancelConnecting: () -> Unit,
    onDismissError: () -> Unit,
    onOpenLauncher: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var tokenFor by remember { mutableStateOf<ServerProfile?>(null) }
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    val busy = connection is ConnectionState.Connecting

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.client_connect_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.client_connect_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        if (connection is ConnectionState.Connecting) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.client_connecting, connection.profile.name), Modifier.weight(1f))
                TextButton(onClick = onCancelConnecting) { Text(stringResource(R.string.client_cancel)) }
            }
        }
        if (connection is ConnectionState.Failed) item {
            val tokenHint = stringResource(R.string.client_token_hint)
            ErrorBanner(
                if (connection.unauthorized) connection.message + "\n" + tokenHint else connection.message,
                onDismiss = onDismissError,
            )
        }

        item { LocalServerCard(local, busy, onConnect = { onConnect(ServerProfile.LOCAL) }, onStart = onStartLocal, onStop = onStopLocal) }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.client_remote_servers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.client_add_server))
                }
            }
        }
        if (profiles.remotes.isEmpty()) item {
            Text(stringResource(R.string.client_no_remote), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        items(profiles.remotes, key = { it.id }) { profile ->
            RemoteCard(
                profile, busy,
                onConnect = { onConnect(profile) },
                onEditToken = { tokenFor = profile },
                onRemove = { removing = profile },
            )
        }

        item {
            TextButton(onClick = onOpenLauncher) { Text(stringResource(R.string.client_open_launcher)) }
        }
    }

    if (showAdd) AddServerDialog(
        onDismiss = { showAdd = false },
        onAdd = { address, token, name -> if (onAddServer(address, token, name)) { showAdd = false; true } else false },
    )
    tokenFor?.let { p ->
        TokenDialog(p, onDismiss = { tokenFor = null }, onSave = { onUpdateToken(p, it); tokenFor = null })
    }
    removing?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.client_remove_server_title),
            text = stringResource(R.string.client_remove_server_text, p.name),
            confirmLabel = stringResource(R.string.client_remove),
            cancelLabel = stringResource(R.string.client_cancel),
            destructive = true,
            onConfirm = { onRemoveServer(p); removing = null },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun LocalServerCard(state: LocalServerState, busy: Boolean, onConnect: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.client_this_device), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.client_this_device_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            val status = when (state) {
                is LocalServerState.Stopped -> stringResource(R.string.client_server_stopped)
                is LocalServerState.Preparing -> if (state.total > 0) stringResource(R.string.client_server_preparing_n, state.copied, state.total) else stringResource(R.string.client_server_preparing)
                is LocalServerState.Starting -> stringResource(R.string.client_server_starting, state.seconds)
                is LocalServerState.Running -> stringResource(if (state.ownedByApp) R.string.client_server_running else R.string.client_server_running_external)
                is LocalServerState.Failed -> state.message
            }
            Text(status, color = if (state is LocalServerState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is LocalServerState.Running -> {
                        Button(onClick = onConnect, enabled = !busy) { Text(stringResource(R.string.client_connect)) }
                        if (state.ownedByApp) OutlinedButton(onClick = onStop) { Text(stringResource(R.string.client_stop_server)) }
                    }
                    is LocalServerState.Preparing, is LocalServerState.Starting -> Unit
                    else -> Button(onClick = onConnect, enabled = !busy) { Text(stringResource(R.string.client_start_and_connect)) }
                }
            }
        }
    }
}

@Composable
private fun RemoteCard(profile: ServerProfile, busy: Boolean, onConnect: () -> Unit, onEditToken: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Text(profile.baseUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (profile.token == null) Text(stringResource(R.string.client_no_token), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onConnect, enabled = !busy) { Text(stringResource(R.string.client_connect)) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.client_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.client_change_token)) }, onClick = { menu = false; onEditToken() })
                DropdownMenuItem(text = { Text(stringResource(R.string.client_remove)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onAdd: (address: String, token: String, name: String) -> Boolean) {
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.client_add_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it; invalid = false },
                    label = { Text(stringResource(R.string.client_address_label)) },
                    supportingText = { Text(stringResource(if (invalid) R.string.client_address_invalid else R.string.client_address_hint)) },
                    isError = invalid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(stringResource(R.string.client_token_label)) },
                    supportingText = { Text(stringResource(R.string.client_token_optional_hint)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.client_name_label)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { if (!onAdd(address, token, name)) invalid = true }) { Text(stringResource(R.string.client_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.client_cancel)) } },
    )
}

@Composable
private fun TokenDialog(profile: ServerProfile, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.client_change_token)) },
        text = {
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(stringResource(R.string.client_token_label)) },
                supportingText = { Text(profile.name) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(token) }) { Text(stringResource(R.string.client_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.client_cancel)) } },
    )
}
