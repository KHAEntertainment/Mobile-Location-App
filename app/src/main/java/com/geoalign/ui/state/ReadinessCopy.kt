package com.geoalign.ui.state

/**
 * All user-facing wording for the readiness screen, behind an interface so tests can assert on
 * identity ([NoteId], [ActionId], [StatusTone]) rather than prose — copy edits then cost nothing.
 *
 * This is also the migration path to strings.xml: that becomes another implementation reading from
 * Resources, with no change to the presenter.
 */
interface ReadinessCopy {

    val checking: String
    val aligned: String
    val noProfile: String
    val exitUnknown: String
    val driftedCountry: String
    val driftedCity: String
    val driftedDistance: String
    val staleCapture: String
    val blockedNoVpn: String
    val blockedNoNetwork: String
    val blockedVpnDropped: String
    val errorHeadline: String

    val transportDetected: String
    val transportNotDetected: String
    val transportError: String
    val transportNoNetwork: String
    val transportChecking: String

    val notCheckedYet: String
    val checkedMomentsAgo: String
    fun checkedMinutesAgo(minutes: Long): String
    fun checkedHoursAgo(hours: Long): String
    val checkedOverADayAgo: String

    fun note(id: NoteId): String
    fun action(id: ActionId): String

    val noVpnPrompt: NoVpnPrompt
    val connectionDetailsLabel: String
    val diagnosticsLabel: String
    val disclaimerShort: String
    val disclaimerFull: String

    object En : ReadinessCopy {
        override val checking = "Checking…"
        override val aligned = "Browser aligned"
        override val noProfile = "No browser profile yet"
        override val exitUnknown = "Can't confirm your exit"
        override val driftedCountry = "Profile doesn't match your current exit"
        override val driftedCity = "Profile is in a different city than your exit"
        override val driftedDistance = "Profile is far from your current exit"
        override val staleCapture = "Profile was saved from an out-of-date estimate"
        override val blockedNoVpn = "No VPN detected"
        override val blockedNoNetwork = "No network"
        override val blockedVpnDropped = "VPN connection dropped"
        override val errorHeadline = "Couldn't check readiness"

        override val transportDetected = "VPN detected"
        override val transportNotDetected = "No VPN detected"
        override val transportError = "VPN status unconfirmed"
        override val transportNoNetwork = "No network"
        override val transportChecking = "Checking connection"

        override val notCheckedYet = "Not checked yet"
        override val checkedMomentsAgo = "Checked moments ago"
        override fun checkedMinutesAgo(minutes: Long) =
            if (minutes == 1L) "Checked 1 min ago" else "Checked $minutes min ago"
        override fun checkedHoursAgo(hours: Long) =
            if (hours == 1L) "Checked 1 hr ago" else "Checked $hours hr ago"
        override val checkedOverADayAgo = "Checked over a day ago"

        override fun note(id: NoteId): String = when (id) {
            NoteId.NO_VPN_ACCEPTED -> "You chose to continue without a VPN. Your real IP may be exposed."
            NoteId.VPN_DROPPED_LIVE -> "The VPN connection dropped. Check again before browsing."
            NoteId.INTERNET_UNREACHABLE -> "The internet doesn't appear reachable on this network."
            NoteId.IP_UNVERIFIED -> "Couldn't verify your public IP, so this estimate may be wrong."
            NoteId.GEO_FAILED -> "Couldn't look up a location for your connection."
            NoteId.IP_STACK_DIVERGENCE -> "IPv4 and IPv6 exit in different places — a possible leak."
            NoteId.DRIFT -> "Re-match to align the browser with where you're actually exiting."
            NoteId.STALE_CAPTURE -> "Re-match so the profile reflects your current exit."
            NoteId.EXIT_IP_CHANGED ->
                "Your connection is now exiting from a different address than when this was checked."
            NoteId.UNABLE_TO_VERIFY ->
                "Couldn't confirm your current exit. This is not a pass — check again before browsing."
            NoteId.STALE_EVALUATION -> "This reading is a few minutes old."
            NoteId.NO_PROFILE -> "Match the browser to your VPN to get started."
            NoteId.ERROR -> "Something went wrong while checking."
        }

        override fun action(id: ActionId): String = when (id) {
            ActionId.OPEN_BROWSER -> "Open browser"
            ActionId.REMATCH -> "Re-match"
            ActionId.EDIT_PROFILE -> "Edit profile"
            ActionId.REFRESH -> "Check again"
            ActionId.CONTINUE_WITHOUT_VPN -> "Continue without a VPN"
            ActionId.OPEN_CONNECTION_DETAILS -> "Connection details"
            ActionId.OPEN_DIAGNOSTICS -> "Diagnostics"
            ActionId.SHOW_DISCLAIMER -> "What GeoAlign does"
        }

        override val noVpnPrompt = NoVpnPrompt(
            title = "Continue without a VPN?",
            body = "No VPN transport is detected, so websites can see your real public IP address " +
                "and network provider. GeoAlign changes what pages see inside its browser — it " +
                "cannot hide where your connection comes from.",
            confirmLabel = "Continue anyway",
            dismissLabel = "Cancel",
        )

        override val connectionDetailsLabel = "Connection details"
        override val diagnosticsLabel = "Diagnostics"
        override val disclaimerShort = "GeoAlign changes browser-visible signals only."
        override val disclaimerFull =
            "GeoAlign does not operate your VPN and does not change Android's system location. " +
                "Location, timezone and language changes apply only inside the embedded browser, " +
                "and only to what pages can see. This is a consistency tool, not an anonymity " +
                "guarantee — it does not hide your IP address and does not promise anonymity."
    }
}
