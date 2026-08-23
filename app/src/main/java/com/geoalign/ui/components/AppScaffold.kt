package com.geoalign.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.geoalign.ui.theme.Sizes
import com.geoalign.ui.theme.Spacing

/**
 * The common page frame: background, a compact title bar, and a horizontally-inset content column.
 *
 * Deliberately not M3 [androidx.compose.material3.TopAppBar] — that needs an experimental opt-in
 * and brings a scroll-behaviour model this app has no use for. A plain Row is less machinery for
 * the same result.
 *
 * @param scrollable false when the content manages its own scrolling or fills the height (a
 *   WebView, a lazy list). A nested vertical scroll around either would break layout.
 */
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    contentSpacing: Dp = Spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = title, onBack = onBack, trailing = trailing)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = Spacing.page)
                    .padding(bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                content = content,
            )
        }
    }
}

@Composable
private fun AppTopBar(
    title: String,
    onBack: (() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.topBarHeight)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .size(Sizes.minTouch)
                    .semantics { contentDescription = "Back" }
                    .testTag("app_scaffold_back"),
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            Spacer(Modifier.size(Spacing.md))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.xs)
                .testTag("app_scaffold_title"),
        )
        trailing()
    }
}
