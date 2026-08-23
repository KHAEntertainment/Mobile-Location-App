package com.geoalign.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.geoalign.ui.theme.GeoShapeTokens
import com.geoalign.ui.theme.Sizes
import com.geoalign.ui.theme.Spacing

/** The single strong call to action on a screen. There should never be two. */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = GeoShapeTokens.button,
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.primaryButtonHeight)
            .testTag("primary_action"),
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = GeoShapeTokens.button,
        modifier = modifier
            .height(Sizes.secondaryButtonHeight)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        // Long labels at large font scales would otherwise push the pair out of the row.
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lays out sibling secondary actions with equal width. */
@Composable
fun SecondaryActionRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}
