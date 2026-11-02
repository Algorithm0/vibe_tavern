package com.vibetavern.launcher.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.vibetavern.client.data.RpText

/** Round avatar from the server, falling back to the name's initial while loading or when there is none. */
@Composable
/** [model] is a URL string, picked image bytes, or null. */
fun Avatar(model: Any?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val initial = remember(name) { name.trim().firstOrNull()?.uppercase() ?: "?" }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val fallback: @Composable () -> Unit = {
            Text(initial, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold)
        }
        if (model == null) fallback() else SubcomposeAsyncImage(
            model = model,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

/** Roleplay formatting (`*action*`, "speech") as styled text. */
@Composable
fun rpAnnotated(text: String, base: Color = MaterialTheme.colorScheme.onSurface): AnnotatedString {
    val speech = VtColors.Speech
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(text, base, speech, secondary) {
        buildAnnotatedString {
            for (seg in RpText.parse(text)) {
                val style = SpanStyle(
                    color = when {
                        seg.quoted -> speech
                        seg.italic -> secondary
                        else -> base
                    },
                    fontStyle = if (seg.italic) FontStyle.Italic else null,
                    fontWeight = if (seg.bold) FontWeight.Bold else null,
                )
                withStyle(style) { append(seg.text) }
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("✕") }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    cancelLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}

@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        } else Spacer(Modifier.size(12.dp))
        leading?.invoke()
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}
