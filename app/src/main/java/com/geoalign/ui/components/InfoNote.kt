package com.geoalign.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.geoalign.ui.theme.Sizes
import com.geoalign.ui.theme.Spacing

/**
 * One-line honesty note that opens the full explanation.
 *
 * The marker is drawn rather than using U+24D8 (ⓘ), which falls back to a tofu box on several OEM
 * font stacks — the app has no icon dependency, so glyph availability is a real constraint.
 */
@Composable
fun InfoNote(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$text. Tap for the full explanation."
            }
            .padding(vertical = Spacing.sm)
            .testTag("info_note"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "i",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm),
        )
    }
}

@Composable
fun GeoInfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("info_dialog_dismiss")) {
                Text("Got it")
            }
        },
    )
}
