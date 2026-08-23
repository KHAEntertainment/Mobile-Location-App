package com.geoalign.ui.state

import com.geoalign.core.readiness.VpnTransport

/**
 * Whether the user's "continue without a VPN" opt-in should still stand.
 *
 * Acceptance is session-only and never persisted, and it clears the moment a VPN is detected. Once
 * a VPN comes up, a later drop is a new situation the user has not agreed to — inheriting the old
 * opt-in would silently un-block the screen at exactly the moment it should block.
 */
object NoVpnAcceptance {

    fun next(current: Boolean, transport: VpnTransport): Boolean = when (transport) {
        VpnTransport.DETECTED -> false
        else -> current
    }
}
