package com.geoalign.ui.state

/**
 * The complete description of the readiness screen, derived from observed facts by
 * [ReadinessPresenter]. Deliberately free of Compose and Android types: the screen renders this
 * and decides nothing, so every decision worth getting right is reachable from a JVM unit test.
 *
 * That split is the direct response to the bug in 3d3108b, which lived in a Composable — business
 * logic inside a @Composable had no test harness at all.
 */
enum class LoadPhase { INITIAL, REFRESHING, LOADED, ERROR }

/** Semantic tone. Mapped to colour by the theme; VERIFIED is the only one that renders green. */
enum class StatusTone { VERIFIED, NEUTRAL, ATTENTION, BLOCKED }

enum class StatusGlyph { CHECK, ALERT, SPINNER, NONE }

enum class ActionId {
    OPEN_BROWSER,
    REMATCH,
    EDIT_PROFILE,
    REFRESH,
    CONTINUE_WITHOUT_VPN,
    OPEN_CONNECTION_DETAILS,
    OPEN_DIAGNOSTICS,
    SHOW_DISCLAIMER,
}

enum class Emphasis { PRIMARY, SECONDARY, TEXT }

enum class NoteId {
    NO_VPN_ACCEPTED,
    VPN_DROPPED_LIVE,
    INTERNET_UNREACHABLE,
    IP_UNVERIFIED,
    GEO_FAILED,
    IP_STACK_DIVERGENCE,
    DRIFT,
    STALE_CAPTURE,
    EXIT_IP_CHANGED,
    UNABLE_TO_VERIFY,
    STALE_EVALUATION,
    NO_PROFILE,
    ERROR,
}

data class StatusNote(val id: NoteId, val tone: StatusTone, val text: String)

/**
 * The one status surface. [transportTone] is separate from [tone] on purpose: whether a VPN is
 * present is the most safety-critical fact on the screen, and it should be able to go red on its
 * own rather than sitting in grey next to a timestamp.
 */
data class StatusBlock(
    val tone: StatusTone,
    val glyph: StatusGlyph,
    val headline: String,
    val exitLine: String?,
    val transportLine: String?,
    val transportTone: StatusTone,
    val freshnessLine: String?,
    val notes: List<StatusNote>,
)

data class ActionState(
    val id: ActionId,
    val label: String,
    val enabled: Boolean,
    val emphasis: Emphasis,
)

data class NoVpnPrompt(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val dismissLabel: String,
)

data class DetailRow(val label: String, val value: String, val mono: Boolean)

data class DisclosureItem(val id: ActionId, val label: String, val summary: String?)

data class ReadinessUiState(
    val status: StatusBlock,
    val refresh: ActionState,
    val primaryAction: ActionState,
    val secondaryActions: List<ActionState>,
    /** Non-null only when there is genuinely something for the user to accept. */
    val noVpnPrompt: NoVpnPrompt?,
    val disclosures: List<DisclosureItem>,
    val connectionDetails: List<DetailRow>,
    val disclaimerShort: String,
    val disclaimerFull: String,
) {
    /** Every action on screen, for invariant checks. */
    val allActions: List<ActionState> get() = listOf(primaryAction) + secondaryActions + refresh
}
