package com.geoalign.ui.readiness

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.geoalign.ui.components.StatusBadge
import com.geoalign.ui.components.statusAccentColor
import com.geoalign.ui.state.StatusBlock
import com.geoalign.ui.state.StatusTone
import com.geoalign.ui.theme.GeoShapeTokens
import com.geoalign.ui.theme.Sizes
import com.geoalign.ui.theme.Spacing

/**
 * The single status surface: what the browser's alignment is, where it is exiting, whether a VPN
 * is present, and how fresh the reading is — plus any warnings, in the same place rather than as
 * extra cards below.
 *
 * Stateless by design. It is a pure function of one immutable [StatusBlock], holds no repository
 * and starts no effects, so anything that can produce a [StatusBlock] can drive it — including a
 * live VPN-transport stream later, which changes only [StatusBlock.transportLine],
 * [StatusBlock.transportTone] and the note list.
 */
@Composable
fun AlignmentStatusHeader(
    status: StatusBlock,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GeoShapeTokens.statusSurface)
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.xl)
            .testTag("alignment_status_header"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(tone = status.tone, glyph = status.glyph)
            Text(
                text = status.headline,
                style = MaterialTheme.typography.headlineSmall,
                color = headlineColor(status.tone),
                modifier = Modifier
                    .padding(start = Spacing.md)
                    .testTag("status_headline"),
            )
        }

        status.exitLine?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("status_exit_line"),
            )
        }

        // Transport and freshness share a line, but the transport segment carries its own colour:
        // whether a VPN is present is the most safety-critical fact here and must not read as
        // quiet grey metadata when it is bad news.
        Row(verticalAlignment = Alignment.CenterVertically) {
            status.transportLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusAccentColor(status.transportTone),
                    modifier = Modifier.testTag("status_transport_line"),
                )
            }
            status.freshnessLine?.let {
                Text(
                    text = if (status.transportLine != null) " · $it" else it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("status_freshness_line"),
                )
            }
            TextButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier
                    .size(Sizes.minTouch)
                    .semantics { contentDescription = "Check again" }
                    .testTag("status_refresh"),
            ) {
                Text("↻", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (status.notes.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                status.notes.forEach { note ->
                    Text(
                        text = note.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusAccentColor(note.tone),
                        modifier = Modifier.testTag("status_note_${note.id.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun headlineColor(tone: StatusTone) = when (tone) {
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    else -> statusAccentColor(tone)
}
