package com.geoalign.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.geoalign.ui.theme.GeoTextStyles
import com.geoalign.ui.theme.GeoTheme
import com.geoalign.ui.theme.Spacing

/**
 * Label/value pair. [mono] is for values a user might read character by character or compare —
 * IP addresses and coordinates — and nothing else.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (mono) GeoTextStyles.mono else MaterialTheme.typography.bodyMedium,
            color = if (mono) GeoTheme.status.monoText else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f),
        )
    }
}
