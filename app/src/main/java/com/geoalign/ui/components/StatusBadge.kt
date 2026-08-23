package com.geoalign.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.geoalign.ui.state.StatusGlyph
import com.geoalign.ui.state.StatusTone
import com.geoalign.ui.theme.GeoTheme
import com.geoalign.ui.theme.Sizes

/**
 * The tinted mark beside the status headline. Purely decorative — the headline carries the meaning
 * in words, so this is hidden from accessibility services rather than read out as "check".
 */
@Composable
fun StatusBadge(tone: StatusTone, glyph: StatusGlyph, modifier: Modifier = Modifier) {
    if (glyph == StatusGlyph.NONE) return
    val container = statusContainerColor(tone)
    val content = statusContentColor(tone)
    Box(
        modifier = modifier
            .size(Sizes.statusBadge)
            .background(container, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        when (glyph) {
            StatusGlyph.SPINNER -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = content,
            )
            StatusGlyph.CHECK -> Text("✓", style = MaterialTheme.typography.labelLarge, color = content)
            StatusGlyph.ALERT -> Text("!", style = MaterialTheme.typography.labelLarge, color = content)
            StatusGlyph.NONE -> Unit
        }
    }
}

@Composable
fun statusContainerColor(tone: StatusTone): Color = when (tone) {
    StatusTone.VERIFIED -> GeoTheme.status.verifiedContainer
    StatusTone.ATTENTION -> GeoTheme.status.attentionContainer
    StatusTone.BLOCKED -> GeoTheme.status.blockedContainer
    StatusTone.NEUTRAL -> GeoTheme.status.neutralContainer
}

@Composable
fun statusContentColor(tone: StatusTone): Color = when (tone) {
    StatusTone.VERIFIED -> GeoTheme.status.onVerifiedContainer
    StatusTone.ATTENTION -> GeoTheme.status.onAttentionContainer
    StatusTone.BLOCKED -> GeoTheme.status.onBlockedContainer
    StatusTone.NEUTRAL -> GeoTheme.status.onNeutralContainer
}

/** Emphasis colour for a status word used inline (e.g. the transport line). */
@Composable
fun statusAccentColor(tone: StatusTone): Color = when (tone) {
    StatusTone.VERIFIED -> GeoTheme.status.verified
    StatusTone.ATTENTION -> GeoTheme.status.attention
    StatusTone.BLOCKED -> GeoTheme.status.blocked
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}
