package com.geoalign.data.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.geoalign.core.readiness.VpnTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Observes whether Android reports an active VPN transport on the app's default network
 * (spec §6). It reports ONLY the transport fact — it explicitly does not claim that all traffic
 * is tunneled, that DNS uses the VPN, or that the public IP changed. Those are verified elsewhere.
 */
interface VpnStatusRepository {
    /** One-shot check of the current default network. */
    fun currentTransport(): VpnTransport

    /** Emits on connectivity changes so readiness can re-check automatically. */
    fun transportUpdates(): Flow<VpnTransport>
}

class AndroidVpnStatusRepository(context: Context) : VpnStatusRepository {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun currentTransport(): VpnTransport {
        val network: Network = cm.activeNetwork ?: return VpnTransport.NETWORK_UNAVAILABLE
        val caps = cm.getNetworkCapabilities(network) ?: return VpnTransport.NETWORK_UNAVAILABLE
        return classify(caps)
    }

    override fun transportUpdates(): Flow<VpnTransport> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(classify(caps))
            }

            override fun onLost(network: Network) {
                trySend(currentTransport())
            }

            override fun onUnavailable() {
                trySend(VpnTransport.NETWORK_UNAVAILABLE)
            }
        }
        // Emit the current state immediately, then stream changes.
        trySend(currentTransport())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    private fun classify(caps: NetworkCapabilities): VpnTransport {
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VpnTransport.DETECTED
            hasInternet -> VpnTransport.NOT_DETECTED
            else -> VpnTransport.NETWORK_UNAVAILABLE
        }
    }
}
