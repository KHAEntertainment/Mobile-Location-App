package com.geoalign.ui.readiness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.geoalign.ui.components.KeyValueRow
import com.geoalign.ui.state.DetailRow

/**
 * The technical readout, moved off the front page. Keeping it in a dialog rather than an inline
 * expansion means the readiness screen has a fixed, scannable height regardless of how many fields
 * the provider happened to return.
 */
@Composable
fun ConnectionDetailsDialog(
    rows: List<DetailRow>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection details", style = MaterialTheme.typography.titleMedium) },
        text = {
            if (rows.isEmpty()) {
                Text(
                    "Nothing to show yet — check again once a connection has been measured.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .testTag("connection_details_rows"),
                ) {
                    rows.forEach { KeyValueRow(it.label, it.value, mono = it.mono) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("connection_details_dismiss")) {
                Text("Close")
            }
        },
    )
}
