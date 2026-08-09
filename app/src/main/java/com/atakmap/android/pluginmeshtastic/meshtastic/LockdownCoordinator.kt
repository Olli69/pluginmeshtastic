package com.atakmap.android.pluginmeshtastic.meshtastic

import android.content.Context
import android.util.Log
import com.geeksville.mesh.MeshProtos as GeneratedMeshProtos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hook used by [LockdownCoordinator] to emit AdminMessage.lockdown_auth packets without
 * dragging the entire [MeshtasticManager] into the unit-test surface.
 */
interface LockdownSender {
    /**
     * Send AdminMessage.lockdown_auth with passphrase + boots/hours + optional per-session
     * uptime cap; disable=false, lock_now=false. Firmware provisions (enables lockdown) when
     * the device isn't yet provisioned, otherwise unlocks.
     */
    fun sendLockdownPassphrase(passphrase: String, boots: Int, hours: Int, maxSessionSeconds: Int)

    /** Send AdminMessage.lockdown_auth with disable=true + passphrase; turns lockdown OFF. */
    fun sendLockdownDisable(passphrase: String)

    /** Send AdminMessage.lockdown_auth with lock_now=true, empty passphrase. */
    fun sendLockNow()

    /** BLE MAC (or USB path) of the currently-connected device, or null. */
    fun getDeviceAddress(): String?
}

/**
 * Drives the lockdown handshake: handles inbound LockdownStatus events, auto-replays
 * the cached passphrase silently, surfaces a dialog only when no cached passphrase
 * exists or auto-unlock fails, and synthesizes [LockdownState.LockNowAcknowledged]
 * for the firmware's Lock-Now reboot (which may race the BLE disconnect).
 *
 * State is per-BLE-connection: [onConnect] resets the "this connection is authorized"
 * flag (firmware requires re-auth on every new connection even if storage is unlocked).
 */
class LockdownCoordinator(
    context: Context,
    private val sender: LockdownSender,
) {
    private val passphraseStore = LockdownPassphraseStore(context)

    private val _state = MutableStateFlow<LockdownState>(LockdownState.None)
    val state: StateFlow<LockdownState> = _state

    private val _sessionAuthorized = MutableStateFlow(false)
    val sessionAuthorized: StateFlow<Boolean> = _sessionAuthorized

    @Volatile private var wasAutoAttempt = false
    @Volatile private var pendingPassphrase: String? = null
    @Volatile private var pendingBoots: Int = LockdownPassphraseStore.DEFAULT_BOOTS
    @Volatile private var pendingHours: Int = 0
    @Volatile private var pendingMaxSessionSeconds: Int = 0

    /** Set while a client-initiated disable is in flight, so the next DISABLED is expected. */
    @Volatile private var pendingDisable = false

    /**
     * Set when the operator has requested Lock Now and we are waiting for the firmware's
     * acknowledging LOCKED status. The next inbound LOCKED resolves it as
     * [LockdownState.LockNowAcknowledged]; if the BLE drops first, [onDisconnect] also
     * resolves it.
     */
    @Volatile private var pendingLockNow = false

    fun onConnect() {
        _sessionAuthorized.value = false
        wasAutoAttempt = false
        pendingPassphrase = null
        pendingBoots = LockdownPassphraseStore.DEFAULT_BOOTS
        pendingHours = 0
        pendingMaxSessionSeconds = 0
        pendingLockNow = false
        pendingDisable = false
        _state.value = LockdownState.None
    }

    fun onDisconnect() {
        // If a Lock Now was in flight and the BLE dropped before the status arrived,
        // treat the disconnect itself as the ack so the UI moves on.
        if (pendingLockNow) {
            pendingLockNow = false
            _state.value = LockdownState.LockNowAcknowledged
        } else {
            _state.value = LockdownState.None
        }
        _sessionAuthorized.value = false
        wasAutoAttempt = false
        pendingPassphrase = null
    }

    /**
     * Route an inbound typed LockdownStatus. Caller has already verified
     * `FromRadio.payload_variant == lockdown_status`.
     */
    fun handleLockdownStatus(status: GeneratedMeshProtos.LockdownStatus) {
        Log.i(TAG, "LockdownStatus: state=${status.state} reason='${status.lockReason}' " +
                "boots=${status.bootsRemaining} until=${status.validUntilEpoch} " +
                "backoff=${status.backoffSeconds}")
        when (status.state) {
            GeneratedMeshProtos.LockdownStatus.State.NEEDS_PROVISION -> {
                _state.value = LockdownState.NeedsProvision
            }
            GeneratedMeshProtos.LockdownStatus.State.LOCKED -> handleLocked(status.lockReason ?: "")
            GeneratedMeshProtos.LockdownStatus.State.UNLOCKED -> handleUnlocked(status)
            GeneratedMeshProtos.LockdownStatus.State.UNLOCK_FAILED -> handleUnlockFailed(status.backoffSeconds)
            GeneratedMeshProtos.LockdownStatus.State.DISABLED -> handleDisabled()
            GeneratedMeshProtos.LockdownStatus.State.STATE_UNSPECIFIED,
            GeneratedMeshProtos.LockdownStatus.State.UNRECOGNIZED -> {
                Log.w(TAG, "Ignoring LockdownStatus with unspecified/unknown state")
            }
        }
    }

    private fun handleLocked(reason: String) {
        // Firmware emits a LOCKED status as the Lock-Now ack right before reboot.
        if (pendingLockNow) {
            pendingLockNow = false
            _sessionAuthorized.value = false
            _state.value = LockdownState.LockNowAcknowledged
            return
        }
        val address = sender.getDeviceAddress()
        if (address != null) {
            val stored = passphraseStore.getPassphrase(address)
            if (stored != null) {
                Log.i(TAG, "Auto-replaying cached passphrase for $address (reason=$reason)")
                wasAutoAttempt = true
                pendingPassphrase = stored.passphrase
                pendingBoots = stored.boots
                pendingHours = stored.hours
                pendingMaxSessionSeconds = stored.maxSessionSeconds
                sender.sendLockdownPassphrase(
                    stored.passphrase, stored.boots, stored.hours, stored.maxSessionSeconds
                )
                return
            }
        }
        _state.value = LockdownState.Locked(reason)
    }

    private fun handleUnlocked(status: GeneratedMeshProtos.LockdownStatus) {
        val address = sender.getDeviceAddress()
        val passphrase = pendingPassphrase
        if (address != null && passphrase != null) {
            passphraseStore.savePassphrase(address, passphrase, pendingBoots, pendingHours, pendingMaxSessionSeconds)
            Log.i(TAG, "Saved passphrase for $address")
        }
        pendingPassphrase = null
        wasAutoAttempt = false
        _sessionAuthorized.value = true
        _state.value = LockdownState.Unlocked(
            bootsRemaining = status.bootsRemaining,
            validUntilEpoch = status.validUntilEpoch.toLong() and 0xFFFFFFFFL,
        )
    }

    /**
     * Lockdown is OFF — either the device was never provisioned, or a disable we requested
     * just completed. Drop any cached passphrase (a re-enable will set a fresh one) and clear
     * session state. The UI presents an "Enable lockdown" affordance from here.
     */
    private fun handleDisabled() {
        if (pendingDisable) {
            Log.i(TAG, "Lockdown disabled (client-initiated)")
        } else {
            Log.i(TAG, "Lockdown is not active on this device (capable but disabled)")
        }
        pendingDisable = false
        pendingPassphrase = null
        wasAutoAttempt = false
        sender.getDeviceAddress()?.let { passphraseStore.clearPassphrase(it) }
        _sessionAuthorized.value = false
        _state.value = LockdownState.Disabled
    }

    private fun handleUnlockFailed(backoffSeconds: Int) {
        val wasAuto = wasAutoAttempt
        wasAutoAttempt = false
        pendingPassphrase = null
        if (wasAuto) {
            if (backoffSeconds > 0) {
                Log.i(TAG, "Auto-unlock rate-limited (backoff=${backoffSeconds}s)")
                _state.value = LockdownState.UnlockBackoff(backoffSeconds)
            } else {
                // Assume the cached passphrase was rotated server-side.
                sender.getDeviceAddress()?.let { passphraseStore.clearPassphrase(it) }
                Log.i(TAG, "Auto-unlock failed (wrong passphrase); cleared cached passphrase")
                _state.value = LockdownState.Locked("auto_replay_wrong_passphrase")
            }
            return
        }
        _state.value = if (backoffSeconds > 0) {
            LockdownState.UnlockBackoff(backoffSeconds)
        } else {
            LockdownState.UnlockFailed
        }
    }

    /**
     * User-initiated passphrase submission. Handles all three of provision (enable lockdown on
     * an unprovisioned device), unlock (authorize this connection to a locked device), and
     * re-verify — the firmware decides which based on its own state.
     */
    fun submitPassphrase(passphrase: String, boots: Int, hours: Int, maxSessionSeconds: Int = 0) {
        pendingPassphrase = passphrase
        pendingBoots = boots
        pendingHours = hours
        pendingMaxSessionSeconds = maxSessionSeconds
        wasAutoAttempt = false
        _state.value = LockdownState.None // hide dialog while we wait for the response
        sender.sendLockdownPassphrase(passphrase, boots, hours, maxSessionSeconds)
    }

    /**
     * User-initiated disable: turn lockdown OFF. Requires the current passphrase; the firmware
     * decrypts storage back to plaintext and reboots reporting DISABLED. (Does NOT undo the
     * one-time APPROTECT debug-port burn — the UI must warn about that at enable time.)
     */
    fun disableLockdown(passphrase: String) {
        pendingDisable = true
        wasAutoAttempt = false
        sender.sendLockdownDisable(passphrase)
    }

    /** User-initiated Lock Now. */
    fun lockNow() {
        pendingLockNow = true
        sender.sendLockNow()
    }

    /** Drop any cached passphrase for the current device. */
    fun forgetCachedPassphrase(address: String? = sender.getDeviceAddress()) {
        address?.let { passphraseStore.clearPassphrase(it) }
    }

    companion object {
        private const val TAG = "LockdownCoordinator"
    }
}
