package com.geoalign.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.geoalign.ui.theme.GeoShapeTokens
import com.geoalign.ui.theme.GeoTextStyles
import com.geoalign.ui.theme.Sizes
import com.geoalign.ui.theme.Spacing

/**
 * A tappable row that opens more detail. Used to keep IP/coordinates/timezone and diagnostics off
 * the front page without hiding that they exist.
 */
@Composable
fun DisclosureRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryMono: Boolean = false,
    testTag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(GeoShapeTokens.row)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .heightIn(min = Sizes.disclosureRowHeight)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = if (summaryMono) GeoTextStyles.mono else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
