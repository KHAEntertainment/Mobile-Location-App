package com.geoalign.data.monitor

import com.geoalign.core.alignment.AlignmentThresholds
import com.geoalign.core.model.LocationProfile
import com.geoalign.core.monitor.AlignmentMonitorEvent
import com.geoalign.core.monitor.AlignmentMonitorReducer
import com.geoalign.core.monitor.AlignmentMonitorState
import com.geoalign.core.monitor.MonitorReason
import com.geoalign.data.profiles.ProfileStore
import com.geoalign.data.readiness.ReadinessService
import com.geoalign.data.vpn.VpnStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the readiness screen needs from one live source: the pure [AlignmentMonitorState] plus
 * the raw evaluation behind it, so the screen never has to run its own parallel check to render
 * what the monitor already observed.
 */
data class AlignmentSnapshot(
    val monitor: AlignmentMonitorState = AlignmentMonitorState(),
    val evaluation: ReadinessService.Evaluation? = null,
    val profile: LocationProfile? = null,
    /** When a check last completed. Null until one has. */
    val checkedAtMillis: Long? = null,
    val errorMessage: String? = null,
    /** True while a check is in flight. */
    val checking: Boolean = false,
)

/**
 * Application-scoped live monitor (spec §6, §7). Collects the platform network callback that
 * [VpnStatusRepository.transportUpdates] has exposed since M2 and that nothing in production has
 * ever consumed, and re-verifies alignment whenever the connection underneath the browser changes.
 *
 * This class is a shell on purpose. Every judgement — what a transport change means, whether a
 * completed check counts as aligned, whether a failure may be treated as a pass — belongs to
 * [AlignmentMonitorReducer], which is pure and unit-tested. All this does is move facts between the
 * repositories and the reducer and decide *when* to ask, never *what it means*.
 *
 * It depends only on interfaces and a [CoroutineScope], so it carries no Android imports and is
 * tested on the JVM with fakes — no Robolectric, no Mockito.
 */
class AlignmentMonitor(
    private val readiness: ReadinessService,
    private val profiles: ProfileStore,
    private val vpn: VpnStatusRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val thresholds: AlignmentThresholds = AlignmentThresholds.DEFAULT,
) {
    private val _snapshots = MutableStateFlow(AlignmentSnapshot())
    val snapshots: StateFlow<AlignmentSnapshot> = _snapshots.asStateFlow()

    private val started = AtomicBoolean(false)

    /**
     * Guards against overlapping checks. A network flap can deliver several callbacks in a few
     * hundred milliseconds; without this each one would open its own IP and geolocation request and
     * the last to return — not the last to be observed — would win.
     */
    private val checkInFlight = Mutex()

    @Volatile
    private var userAcceptedNoVpn: Boolean = false

    /** Idempotent. Safe to call from every screen that wants live state. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            vpn.transportUpdates().collect { transport ->
                dispatch(AlignmentMonitorEvent.TransportObserved(transport, clock()))
            }
        }
    }

    /** Manual "Check again", and the lifecycle RESUMED re-evaluation. */
    fun refreshNow(reason: MonitorReason = MonitorReason.MANUAL_REFRESH) {
        dispatch(AlignmentMonitorEvent.CheckRequested(reason, clock()))
    }

    /**
     * The user's standing "continue without a VPN" opt-in, which the readiness reducer needs in
     * order to grade its own output. Held here so automatically-triggered checks evaluate under the
     * same terms as the ones the screen asks for.
     */
    fun setUserAcceptedNoVpn(accepted: Boolean) {
        userAcceptedNoVpn = accepted
    }

    private fun dispatch(event: AlignmentMonitorEvent) {
        val next = _snapshots.updateAndGetSnapshot { current ->
            current.copy(monitor = AlignmentMonitorReducer.reduce(current.monitor, event, thresholds))
        }
        if (next.monitor.needsCheck) launchCheck()
    }

    private fun launchCheck() {
        scope.launch {
            // A check already running will observe at least as recent a world as this one would.
            if (!checkInFlight.tryLock()) return@launch
            try {
                // Whoever wins the lock serves everyone queued behind it. Without this, a burst of
                // callbacks would each open their own IP and geolocation request the moment the
                // first one finished, and the last to *return* would overwrite the last observed.
                if (!_snapshots.value.monitor.needsCheck) return@launch
                runCheck()
            } finally {
                checkInFlight.unlock()
            }
            // Anything observed *during* the check — a transport change, a coalesced request —
            // leaves another one owed. Re-checked after the lock is released, never inside it.
            if (_snapshots.value.monitor.needsCheck) launchCheck()
        }
    }

    private suspend fun runCheck() {
        _snapshots.updateAndGetSnapshot { it.copy(checking = true, errorMessage = null) }

        val outcome = runCatching {
            val profile = profiles.list().firstOrNull()
            val evaluation = readiness.evaluate(
                profileSelected = profile != null,
                userAcceptedNoVpn = userAcceptedNoVpn,
            )
            profile to evaluation
        }

        val at = clock()
        outcome.onSuccess { (profile, evaluation) ->
            val event = AlignmentMonitorEvent.CheckCompleted(
                transport = evaluation.inputs.vpn,
                exitIp = evaluation.effectiveIp?.ip,
                exit = evaluation.geolocation,
                profile = profile,
                atMillis = at,
            )
            _snapshots.updateAndGetSnapshot { current ->
                current.copy(
                    monitor = AlignmentMonitorReducer.reduce(current.monitor, event, thresholds),
                    evaluation = evaluation,
                    profile = profile,
                    checkedAtMillis = at,
                    errorMessage = null,
                    checking = false,
                )
            }
        }.onFailure { error ->
            val message = error.message ?: "readiness check failed"
            val event = AlignmentMonitorEvent.CheckFailed(at, message)
            _snapshots.updateAndGetSnapshot { current ->
                current.copy(
                    monitor = AlignmentMonitorReducer.reduce(current.monitor, event, thresholds),
                    checkedAtMillis = at,
                    errorMessage = message,
                    checking = false,
                )
            }
        }
    }

    private inline fun MutableStateFlow<AlignmentSnapshot>.updateAndGetSnapshot(
        transform: (AlignmentSnapshot) -> AlignmentSnapshot,
    ): AlignmentSnapshot {
        while (true) {
            val current = value
            val next = transform(current)
            if (compareAndSet(current, next)) return next
        }
    }
}
