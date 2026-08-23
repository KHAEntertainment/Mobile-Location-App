package com.geoalign.ui.state

import com.geoalign.core.alignment.AlignmentChecker
import com.geoalign.core.alignment.AlignmentResult
import com.geoalign.core.alignment.AlignmentThresholds
import com.geoalign.core.alignment.AlignmentVerdict
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorStatus
import com.geoalign.core.readiness.ReadinessLevel
import com.geoalign.core.readiness.StepState
import com.geoalign.core.readiness.VpnTransport
import com.geoalign.core.readiness.WarningId
import com.geoalign.data.net.EffectiveIpUtil
import com.geoalign.data.readiness.ReadinessService

data class ReadinessPresentationInput(
    val phase: LoadPhase,
    val evaluation: ReadinessService.Evaluation?,
    val profile: LocationProfile?,
    val errorMessage: String? = null,
    val checkedAtMillis: Long? = null,
    val nowMillis: Long,
    val userAcceptedNoVpn: Boolean = false,
    /**
     * The most recent transport seen by a live network callback, when one is running. Null means
     * "no live signal" and the cached evaluation stands. This exists now, tested, so that wiring
     * VpnStatusRepository.transportUpdates() later is a change to the screen only.
     */
    val liveVpn: VpnTransport? = null,
    /**
     * The live monitor's own verdict, when one is running. It is not a second opinion on alignment
     * — the monitor and this presenter both defer to [AlignmentChecker] — it carries what a
     * single-shot evaluation structurally cannot know: that the exit address moved, or that the
     * last check failed outright. Null means no monitor, and the cached evaluation stands alone.
     */
    val liveMonitor: AlignmentMonitorState? = null,
)

data class PresentationThresholds(
    /** Past this age a VERIFIED reading is downgraded to NEUTRAL — green must mean *now*. */
    val staleEvaluationMillis: Long = 5 * 60_000L,
    val alignment: AlignmentThresholds = AlignmentThresholds.DEFAULT,
) {
    companion object { val DEFAULT = PresentationThresholds() }
}

/**
 * Turns observed facts into the complete screen description.
 *
 * The rule that matters most here: green is the strongest claim this app makes, so [StatusTone
 * .VERIFIED] requires *everything* to be true at once — readiness READY, the profile actually
 * aligned with the live exit, a VPN genuinely detected, a fresh reading, and no standing no-VPN
 * opt-in. A redesign that made a stale or drifted state look confident would be worse than the
 * flat list it replaced.
 */
object ReadinessPresenter {

    fun present(
        input: ReadinessPresentationInput,
        copy: ReadinessCopy = ReadinessCopy.En,
        thresholds: PresentationThresholds = PresentationThresholds.DEFAULT,
    ): ReadinessUiState {
        val eval = input.evaluation
        val geo = eval?.geolocation
        val level = eval?.state?.level
        val busy = input.phase == LoadPhase.INITIAL || input.phase == LoadPhase.REFRESHING

        val alignment = AlignmentChecker.check(
            profile = input.profile,
            exit = geo,
            nowMillis = input.nowMillis,
            thresholds = thresholds.alignment,
        )

        // A live transport reading overrides the cached one when they contradict.
        val cachedVpn = eval?.inputs?.vpn
        val vpn = input.liveVpn ?: cachedVpn
        val vpnDroppedLive = input.liveVpn != null &&
            cachedVpn == VpnTransport.DETECTED &&
            input.liveVpn != VpnTransport.DETECTED

        val evalAge = Freshness.ageMillis(input.checkedAtMillis, input.nowMillis)
        val staleEvaluation = evalAge != null && evalAge > thresholds.staleEvaluationMillis

        val notes = buildNotes(input, eval, alignment, vpnDroppedLive, staleEvaluation, copy)

        // A monitor that is anything other than aligned withholds the green check even when the
        // cached evaluation looks perfect. UNABLE_TO_VERIFY is the case that matters: a check that
        // could not be completed is not a check that passed, and the strongest claim this app makes
        // must never rest on evidence that failed to arrive.
        val monitorAligned = input.liveMonitor == null || input.liveMonitor.isAligned

        val verified = input.phase == LoadPhase.LOADED &&
            level == ReadinessLevel.READY &&
            alignment.verdict == AlignmentVerdict.ALIGNED &&
            vpn == VpnTransport.DETECTED &&
            !staleEvaluation &&
            !input.userAcceptedNoVpn &&
            !vpnDroppedLive &&
            monitorAligned

        val blocked = input.phase != LoadPhase.INITIAL &&
            (vpnDroppedLive || (level == ReadinessLevel.BLOCKED_NO_VPN && !busy))

        val tone = when {
            busy && eval == null -> StatusTone.NEUTRAL
            blocked -> StatusTone.BLOCKED
            verified -> StatusTone.VERIFIED
            input.phase == LoadPhase.ERROR -> StatusTone.ATTENTION
            notes.any { it.tone == StatusTone.ATTENTION } -> StatusTone.ATTENTION
            else -> StatusTone.NEUTRAL
        }

        val glyph = when {
            busy -> StatusGlyph.SPINNER
            tone == StatusTone.VERIFIED -> StatusGlyph.CHECK
            tone == StatusTone.BLOCKED || tone == StatusTone.ATTENTION -> StatusGlyph.ALERT
            else -> StatusGlyph.NONE
        }

        val canOpen = eval?.state?.canOpenBrowser == true && !vpnDroppedLive && !busy
        val canMatch = !busy && geo?.hasCoordinates == true

        // Offered only when there is genuinely something to accept. With no network at all there
        // is no VPN decision to make, and offering one would imply a choice that does not exist.
        val offerNoVpn = !busy &&
            !input.userAcceptedNoVpn &&
            (vpn == VpnTransport.NOT_DETECTED || vpn == VpnTransport.ERROR)

        val secondary = buildList {
            add(ActionState(ActionId.REMATCH, copy.action(ActionId.REMATCH), canMatch, Emphasis.SECONDARY))
            add(ActionState(ActionId.EDIT_PROFILE, copy.action(ActionId.EDIT_PROFILE), !busy, Emphasis.SECONDARY))
            if (offerNoVpn) {
                add(ActionState(
                    ActionId.CONTINUE_WITHOUT_VPN,
                    copy.action(ActionId.CONTINUE_WITHOUT_VPN),
                    enabled = true,
                    emphasis = Emphasis.TEXT,
                ))
            }
            // When blocked or errored, the way forward must not be a single small glyph.
            if (tone == StatusTone.BLOCKED || input.phase == LoadPhase.ERROR) {
                add(ActionState(ActionId.REFRESH, copy.action(ActionId.REFRESH), !busy, Emphasis.SECONDARY))
            }
        }

        val head = headline(input, level, alignment, vpnDroppedLive, busy, copy)

        return ReadinessUiState(
            status = StatusBlock(
                tone = tone,
                glyph = glyph,
                headline = head,
                exitLine = exitLine(input, alignment),
                // Dropped when it would only restate the headline. "No VPN detected" above
                // "No VPN detected" is the same redundancy this screen exists to remove.
                transportLine = transportLine(vpn, copy)?.takeIf { it != head },
                transportTone = transportTone(vpn, input.userAcceptedNoVpn, vpnDroppedLive),
                freshnessLine = Freshness.checkedLabel(input.checkedAtMillis, input.nowMillis, copy),
                notes = notes,
            ),
            refresh = ActionState(ActionId.REFRESH, copy.action(ActionId.REFRESH), !busy, Emphasis.TEXT),
            primaryAction = ActionState(
                ActionId.OPEN_BROWSER, copy.action(ActionId.OPEN_BROWSER), canOpen, Emphasis.PRIMARY,
            ),
            secondaryActions = secondary,
            noVpnPrompt = if (offerNoVpn) copy.noVpnPrompt else null,
            disclosures = listOf(
                DisclosureItem(ActionId.OPEN_CONNECTION_DETAILS, copy.connectionDetailsLabel,
                    eval?.effectiveIp?.let { EffectiveIpUtil.redact(it.ip) }),
                DisclosureItem(ActionId.OPEN_DIAGNOSTICS, copy.diagnosticsLabel, null),
            ),
            connectionDetails = ConnectionDetailsBuilder.rows(eval, input.profile),
            disclaimerShort = copy.disclaimerShort,
            disclaimerFull = copy.disclaimerFull,
        )
    }

    private fun headline(
        input: ReadinessPresentationInput,
        level: ReadinessLevel?,
        alignment: AlignmentResult,
        vpnDroppedLive: Boolean,
        busy: Boolean,
        copy: ReadinessCopy,
    ): String = when {
        vpnDroppedLive -> copy.blockedVpnDropped
        input.phase == LoadPhase.ERROR -> copy.errorHeadline
        busy && input.evaluation == null -> copy.checking
        level == ReadinessLevel.BLOCKED_NO_VPN ->
            if (input.evaluation?.state?.has(WarningId.NO_NETWORK) == true) copy.blockedNoNetwork
            else copy.blockedNoVpn
        else -> when (alignment.verdict) {
            AlignmentVerdict.NO_PROFILE -> copy.noProfile
            AlignmentVerdict.UNKNOWN -> copy.exitUnknown
            AlignmentVerdict.DRIFTED_COUNTRY -> copy.driftedCountry
            AlignmentVerdict.DRIFTED_CITY -> copy.driftedCity
            AlignmentVerdict.DRIFTED_DISTANCE -> copy.driftedDistance
            AlignmentVerdict.STALE_CAPTURE -> copy.staleCapture
            AlignmentVerdict.ALIGNED -> copy.aligned
        }
    }

    /** Prefers the live exit; falls back to the saved profile so the line is never empty. */
    private fun exitLine(input: ReadinessPresentationInput, alignment: AlignmentResult): String? {
        val geo = input.evaluation?.geolocation
        val fromExit = listOfNotNull(
            geo?.city?.trim()?.ifBlank { null },
            geo?.countryName?.trim()?.ifBlank { null } ?: geo?.countryCode?.trim()?.ifBlank { null },
        ).joinToString(", ").ifBlank { null }
        if (fromExit != null) return fromExit
        return listOfNotNull(alignment.profileCity, alignment.profileCountry)
            .joinToString(", ").ifBlank { null }
    }

    private fun transportLine(vpn: VpnTransport?, copy: ReadinessCopy): String? = when (vpn) {
        VpnTransport.DETECTED -> copy.transportDetected
        VpnTransport.NOT_DETECTED -> copy.transportNotDetected
        VpnTransport.ERROR -> copy.transportError
        VpnTransport.NETWORK_UNAVAILABLE -> copy.transportNoNetwork
        VpnTransport.CHECKING -> copy.transportChecking
        null -> null
    }

    private fun transportTone(
        vpn: VpnTransport?,
        accepted: Boolean,
        droppedLive: Boolean,
    ): StatusTone = when {
        droppedLive -> StatusTone.BLOCKED
        vpn == VpnTransport.DETECTED -> StatusTone.VERIFIED
        vpn == VpnTransport.NETWORK_UNAVAILABLE -> StatusTone.BLOCKED
        vpn == VpnTransport.NOT_DETECTED || vpn == VpnTransport.ERROR ->
            if (accepted) StatusTone.ATTENTION else StatusTone.BLOCKED
        else -> StatusTone.NEUTRAL
    }

    private fun buildNotes(
        input: ReadinessPresentationInput,
        eval: ReadinessService.Evaluation?,
        alignment: AlignmentResult,
        vpnDroppedLive: Boolean,
        staleEvaluation: Boolean,
        copy: ReadinessCopy,
    ): List<StatusNote> = buildList {
        fun add(id: NoteId, tone: StatusTone) = add(StatusNote(id, tone, copy.note(id)))

        if (input.phase == LoadPhase.ERROR) add(NoteId.ERROR, StatusTone.ATTENTION)
        if (vpnDroppedLive) add(NoteId.VPN_DROPPED_LIVE, StatusTone.BLOCKED)

        val state = eval?.state
        if (state != null) {
            if (state.has(WarningId.NO_VPN_ACCEPTED)) add(NoteId.NO_VPN_ACCEPTED, StatusTone.ATTENTION)
            if (state.has(WarningId.INTERNET_UNREACHABLE)) add(NoteId.INTERNET_UNREACHABLE, StatusTone.ATTENTION)
            if (state.has(WarningId.EFFECTIVE_IP_FAILED)) add(NoteId.IP_UNVERIFIED, StatusTone.ATTENTION)
            if (state.has(WarningId.GEOLOCATION_FAILED)) add(NoteId.GEO_FAILED, StatusTone.ATTENTION)
            if (state.has(WarningId.IP_STACK_DIVERGENCE)) add(NoteId.IP_STACK_DIVERGENCE, StatusTone.ATTENTION)
        }

        // NO_PROFILE from the reducer is deliberately not echoed: the headline already says it and
        // the note would restate it two lines below in different words.
        if (alignment.verdict == AlignmentVerdict.NO_PROFILE &&
            eval?.geolocation?.hasCoordinates == true
        ) {
            add(NoteId.NO_PROFILE, StatusTone.NEUTRAL)
        }

        when (alignment.verdict) {
            AlignmentVerdict.DRIFTED_COUNTRY,
            AlignmentVerdict.DRIFTED_CITY,
            AlignmentVerdict.DRIFTED_DISTANCE -> add(NoteId.DRIFT, StatusTone.ATTENTION)
            AlignmentVerdict.STALE_CAPTURE -> add(NoteId.STALE_CAPTURE, StatusTone.ATTENTION)
            else -> Unit
        }

        when (input.liveMonitor?.status) {
            MonitorStatus.EXIT_IP_CHANGED -> add(NoteId.EXIT_IP_CHANGED, StatusTone.ATTENTION)
            // Only when nothing else already said why. The reducer's own geolocation and IP
            // warnings are more specific, and stacking a general "couldn't verify" underneath one
            // of them says the same thing twice.
            MonitorStatus.UNABLE_TO_VERIFY ->
                if (none { it.id == NoteId.GEO_FAILED || it.id == NoteId.IP_UNVERIFIED }) {
                    add(NoteId.UNABLE_TO_VERIFY, StatusTone.ATTENTION)
                }
            else -> Unit
        }

        if (staleEvaluation && input.phase == LoadPhase.LOADED) {
            add(NoteId.STALE_EVALUATION, StatusTone.NEUTRAL)
        }
    }
}

/** Everything technical, collapsed behind the "Connection details" disclosure. */
object ConnectionDetailsBuilder {

    fun rows(eval: ReadinessService.Evaluation?, profile: LocationProfile?): List<DetailRow> =
        buildList {
            eval?.effectiveIp?.let {
                add(DetailRow("Effective IP", EffectiveIpUtil.redact(it.ip), mono = true))
                add(DetailRow("IP version", it.version.name, mono = false))
            }
            val geo = eval?.geolocation
            if (geo != null) {
                listOfNotNull(
                    geo.city?.trim()?.ifBlank { null },
                    geo.countryName?.trim()?.ifBlank { null } ?: geo.countryCode?.trim()?.ifBlank { null },
                ).joinToString(", ").ifBlank { null }
                    ?.let { add(DetailRow("Approximate exit", it, mono = false)) }

                if (geo.latitude != null && geo.longitude != null) {
                    add(DetailRow(
                        "Coordinates",
                        "%.4f, %.4f".format(geo.latitude, geo.longitude),
                        mono = true,
                    ))
                }
                geo.timezone?.trim()?.ifBlank { null }
                    ?.let { add(DetailRow("Timezone", it, mono = false)) }
                geo.org?.trim()?.ifBlank { null }
                    ?.let { add(DetailRow("Network", it, mono = false)) }
                geo.providerName.trim().ifBlank { null }
                    ?.let { add(DetailRow("Estimate source", it, mono = false)) }
            }
            if (profile != null) {
                add(DetailRow("Profile", profile.name, mono = false))
                add(DetailRow("Profile timezone", profile.timezone, mono = false))
                add(DetailRow("Profile language", profile.primaryLocale, mono = false))
            }
        }
}
