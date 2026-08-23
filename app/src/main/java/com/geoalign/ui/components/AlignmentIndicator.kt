package com.geoalign.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.geoalign.core.browser.AlignmentRecoveryAction
import com.geoalign.ui.state.AlignmentActionState
import com.geoalign.ui.state.AlignmentPrompt
import com.geoalign.ui.state.BrowserAlignmentUiState
import com.geoalign.ui.state.Emphasis
import com.geoalign.ui.state.StatusGlyph
import com.geoalign.ui.state.StatusTone
import com.geoalign.ui.theme.Spacing

/**
 * The persistent alignment indicator in the browser chrome (issue #6).
 *
 * Stateless and a pure function of one [BrowserAlignmentUiState], which
 * `BrowserAlignmentPresenter` produced from the live monitor. Nothing here decides anything: the
 * tone arrives already chosen, so the rule that green means verified alignment is enforced where a
 * unit test can see it rather than in a `when` inside a composable.
 *
 * Colours come from `GeoTheme.status` via [statusContainerColor] / [statusContentColor], never from
 * the Material `ColorScheme` — green lives outside the scheme precisely so no component can inherit
 * it by accident.
 */
@Composable
fun AlignmentIndicator(state: BrowserAlignmentUiState, modifier: Modifier = Modifier) {
    val container = statusContainerColor(state.tone)
    val content = statusContentColor(state.tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics { contentDescription = state.contentDescription }
            .testTag("browser_alignment_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        when (state.glyph) {
            StatusGlyph.SPINNER -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = content,
            )
            StatusGlyph.CHECK -> Text("✓", style = MaterialTheme.typography.labelMedium, color = content)
            StatusGlyph.ALERT -> Text("!", style = MaterialTheme.typography.labelMedium, color = content)
            StatusGlyph.NONE -> Unit
        }
        Text(
            text = state.indicatorLabel,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.testTag("browser_alignment_label"),
        )
    }
}

/**
 * The standing warning shown while browsing continues under an accepted risk.
 *
 * It does not go away, and it is not dismissible. The whole point of "continue with a warning" is
 * the warning; a banner the user could tap away would leave the browser looking ordinary while the
 * environment stays unverified.
 */
@Composable
fun AlignmentWarningBanner(state: BrowserAlignmentUiState, modifier: Modifier = Modifier) {
    val text = state.banner ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = statusContentColor(state.tone),
        modifier = modifier
            .fillMaxWidth()
            .background(statusContainerColor(state.tone))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .testTag("browser_alignment_banner"),
    )
}

/**
 * The four recovery choices.
 *
 * Laid out as a full-width **column**, not a button row. At 1.5x font scale a row of four labels
 * this long cannot fit on a phone, and the two that would be pushed off the edge are "Continue
 * anyway" and "Leave the browser" — the two the user most needs when the connection under them has
 * just gone. Stacking costs vertical space and never truncates.
 *
 * There is deliberately no dismiss affordance: leaving is one of the four choices, and a fifth way
 * out that silently resumes navigation would defeat the pause.
 */
@Composable
fun AlignmentRecoveryPanel(
    prompt: AlignmentPrompt,
    errorMessage: String?,
    onAction: (AlignmentRecoveryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.lg)
            .testTag("browser_alignment_prompt"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(prompt.title, style = MaterialTheme.typography.titleMedium)
        Text(prompt.body, style = MaterialTheme.typography.bodyMedium)
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = statusContentColor(StatusTone.BLOCKED),
                modifier = Modifier.testTag("browser_alignment_prompt_error"),
            )
        }
        prompt.actions.forEach { action ->
            AlignmentActionButton(action, onAction)
        }
    }
}

@Composable
private fun AlignmentActionButton(
    action: AlignmentActionState,
    onAction: (AlignmentRecoveryAction) -> Unit,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .testTag("browser_alignment_action_${action.id.name}")
    val label: @Composable () -> Unit = { Text(action.label) }
    when (action.emphasis) {
        Emphasis.PRIMARY ->
            Button(onClick = { onAction(action.id) }, enabled = action.enabled, modifier = modifier) { label() }
        Emphasis.SECONDARY ->
            OutlinedButton(onClick = { onAction(action.id) }, enabled = action.enabled, modifier = modifier) { label() }
        Emphasis.TEXT ->
            TextButton(onClick = { onAction(action.id) }, enabled = action.enabled, modifier = modifier) { label() }
    }
}
