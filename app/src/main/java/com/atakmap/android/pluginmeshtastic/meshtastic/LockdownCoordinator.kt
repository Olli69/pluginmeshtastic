package com.atakmap.android.pluginmeshtastic.meshtastic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hook used by [LockdownCoordinator] to send lockdown AdminMessage packets without dragging
 * the whole [MeshtasticManager] into the unit-test surface.
 */
interface LockdownSender {
    fun sendLockdownPassphrase(passphrase: String, boots: Int, hours: Int)
    fun sendLockNow()
    /** Returns the BLE MAC (or USB path) of the currently-connected device, or null. */
    fun getDeviceAddress(): String?
}

/**
 * Coordinates the lockdown handshake — drives a [LockdownState] flow that the UI subscribes
 * to, auto-replays the cached passphrase silently, and only surfaces a dialog when no cached
 * passphrase exists or auto-unlock fails.
 *
 * State is per-BLE-connection: [onConnect] resets the "this connection is authorized" flag
 * (the firmware requires re-auth on every new connection, even if storage is already unlocked).
 */
class LockdownCoordinator(
    context: Context,
    private val sender: LockdownSender,
) {
    private val passphraseStore = LockdownPassphraseStore(context)

    private val _state = MutableStateFlow<LockdownState>(LockdownState.None)
    val state: StateFlow<LockdownState> = _state

    private val _tokenInfo = MutableStateFlow<LockdownTokenInfo?>(null)
    val tokenInfo: StateFlow<LockdownTokenInfo?> = _tokenInfo

    private val _sessionAuthorized = MutableStateFlow(false)
    val sessionAuthorized: StateFlow<Boolean> = _sessionAuthorized

    @Volatile private var wasAutoAttempt = false
    @Volatile private var pendingPassphrase: String? = null
    @Volatile private var pendingBoots: Int = LockdownPassphraseStore.DEFAULT_BOOTS
    @Volatile private var pendingHours: Int = 0

    /** Call when a fresh BLE/USB connection comes up. */
    fun onConnect() {
        _sessionAuthorized.value = false
        wasAutoAttempt = false
        pendingPassphrase = null
        pendingBoots = LockdownPassphraseStore.DEFAULT_BOOTS
        pendingHours = 0
        // Don't preemptively switch to Locked — wait for the device to tell us.
        _state.value = LockdownState.None
        _tokenInfo.value = null
    }

    /** Call when the BLE/USB connection drops. */
    fun onDisconnect() {
        _sessionAuthorized.value = false
        wasAutoAttempt = false
        pendingPassphrase = null
        _tokenInfo.value = null
        _state.value = LockdownState.None
    }

    /**
     * Route an incoming ClientNotification message that starts with `LOCKDOWN_`.
     * Caller is responsible for checking the prefix.
     */
    fun handleLockdownNotification(message: String?) {
        if (message == null) return
        Log.i(TAG, "Lockdown notification: $message")
        when {
            message == LOCKDOWN_NEEDS_PROVISION -> _state.value = LockdownState.NeedsProvision
            message == LOCKDOWN_LOCKED_ACK -> handleLockNowAcknowledged()
            message.startsWith(LOCKDOWN_LOCKED_WITH_REASON_PREFIX) -> handleLocked()
            message.startsWith(LOCKDOWN_UNLOCKED_PREFIX) -> handleUnlocked(message)
            message.startsWith(LOCKDOWN_UNLOCK_FAILED_PREFIX) -> handleUnlockFailed(message)
            else -> {
                Log.w(TAG, "Unrecognized LOCKDOWN_ notification; treating as Locked")
                _state.value = LockdownState.Locked
            }
        }
    }

    private fun handleLockNowAcknowledged() {
        Log.i(TAG, "Lock Now acknowledged — clearing session authorization")
        _sessionAuthorized.value = false
        wasAutoAttempt = false
        pendingPassphrase = null
        _state.value = LockdownState.LockNowAcknowledged
    }

    private fun handleLocked() {
        val address = sender.getDeviceAddress()
        if (address != null) {
            val stored = passphraseStore.getPassphrase(address)
            if (stored != null) {
                Log.i(TAG, "Auto-replaying cached passphrase for $address")
                wasAutoAttempt = true
                pendingPassphrase = stored.passphrase
                pendingBoots = stored.boots
                pendingHours = stored.hours
                sender.sendLockdownPassphrase(stored.passphrase, stored.boots, stored.hours)
                return
            }
        }
        _state.value = LockdownState.Locked
    }

    private fun handleUnlocked(message: String) {
        val address = sender.getDeviceAddress()
        val passphrase = pendingPassphrase
        if (address != null && passphrase != null) {
            passphraseStore.savePassphrase(address, passphrase, pendingBoots, pendingHours)
            Log.i(TAG, "Saved passphrase for $address")
        }
        pendingPassphrase = null
        wasAutoAttempt = false
        _tokenInfo.value = parseTokenInfo(message)
        _sessionAuthorized.value = true
        _state.value = LockdownState.Unlocked
    }

    private fun handleUnlockFailed(message: String) {
        val backoffSeconds = message.split(":").firstNotNullOfOrNull { seg ->
            if (seg.startsWith("backoff=")) seg.removePrefix("backoff=").toIntOrNull() else null
        }
        val wasAuto = wasAutoAttempt
        wasAutoAttempt = false
        pendingPassphrase = null
        if (wasAuto) {
            if (backoffSeconds != null && backoffSeconds > 0) {
                Log.i(TAG, "Auto-unlock rate-limited (backoff=${backoffSeconds}s)")
                _state.value = LockdownState.UnlockBackoff(backoffSeconds)
            } else {
                sender.getDeviceAddress()?.let { passphraseStore.clearPassphrase(it) }
                Log.i(TAG, "Auto-unlock failed (wrong passphrase), cleared cached passphrase")
                _state.value = LockdownState.Locked
            }
            return
        }
        if (backoffSeconds != null && backoffSeconds > 0) {
            _state.value = LockdownState.UnlockBackoff(backoffSeconds)
        } else {
            _state.value = LockdownState.UnlockFailed
        }
    }

    private fun parseTokenInfo(message: String): LockdownTokenInfo? {
        var boots = -1
        var until = 0L
        for (segment in message.split(":")) {
            when {
                segment.startsWith("boots=") -> boots = segment.removePrefix("boots=").toIntOrNull() ?: -1
                segment.startsWith("until=") -> until = segment.removePrefix("until=").toLongOrNull() ?: 0L
            }
        }
        return if (boots >= 0) LockdownTokenInfo(boots, until) else null
    }

    /** User-initiated passphrase submission. */
    fun submitPassphrase(passphrase: String, boots: Int, hours: Int) {
        pendingPassphrase = passphrase
        pendingBoots = boots
        pendingHours = hours
        wasAutoAttempt = false
        _state.value = LockdownState.None // hide dialog while we wait for the response
        sender.sendLockdownPassphrase(passphrase, boots, hours)
    }

    /** User-initiated Lock Now. */
    fun lockNow() {
        sender.sendLockNow()
    }

    /** Forget the cached passphrase for the current (or given) device. */
    fun forgetCachedPassphrase(address: String? = sender.getDeviceAddress()) {
        address?.let { passphraseStore.clearPassphrase(it) }
    }

    companion object {
        private const val TAG = "LockdownCoordinator"

        // Wire-format string prefixes from firmware PR #10349. The firmware-side migration to
        // structured LockdownStatus (protobufs PR #911) is a follow-up; when that lands we can
        // add a second observer path here that consumes LockdownStatus and feeds the same state.
        private const val LOCKDOWN_NEEDS_PROVISION = "LOCKDOWN_NEEDS_PROVISION"
        private const val LOCKDOWN_LOCKED_ACK = "LOCKDOWN_LOCKED" // exact match: Lock Now ACK
        private const val LOCKDOWN_LOCKED_WITH_REASON_PREFIX = "LOCKDOWN_LOCKED:"
        private const val LOCKDOWN_UNLOCKED_PREFIX = "LOCKDOWN_UNLOCKED"
        private const val LOCKDOWN_UNLOCK_FAILED_PREFIX = "LOCKDOWN_UNLOCK_FAILED"

        const val PREFIX = "LOCKDOWN_"
    }
}
