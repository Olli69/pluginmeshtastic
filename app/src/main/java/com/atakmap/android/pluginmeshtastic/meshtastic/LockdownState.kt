package com.atakmap.android.pluginmeshtastic.meshtastic

/**
 * Represents the lockdown authentication state for a hardened Meshtastic device.
 *
 * Mirrors the public surface from the Meshtastic-Android reference implementation
 * (org.meshtastic.core.model.service.LockdownState) so the UI can drive the same flows.
 */
sealed class LockdownState {
    /** No lockdown signal received (default for non-hardened devices). */
    object None : LockdownState()

    /** Device is locked and this connection has not been authorized yet. */
    object Locked : LockdownState()

    /** First-boot device with no passphrase set yet — prompt user to pick one. */
    object NeedsProvision : LockdownState()

    /** Session is authorized; optional token metadata in [LockdownCoordinator.tokenInfo]. */
    object Unlocked : LockdownState()

    /** Lock Now ACK received — caller should drop the BLE connection. */
    object LockNowAcknowledged : LockdownState()

    /** Wrong passphrase — retry immediately. */
    object UnlockFailed : LockdownState()

    /** Too many failed attempts — caller must wait [backoffSeconds] before retrying. */
    data class UnlockBackoff(val backoffSeconds: Int) : LockdownState()
}

/**
 * Parsed metadata from a LOCKDOWN_UNLOCKED:boots=N:until=EPOCH notification.
 *
 * @param bootsRemaining reboots before the token expires.
 * @param expiryEpoch unix epoch seconds; 0 = no wall-clock expiry.
 */
data class LockdownTokenInfo(
    val bootsRemaining: Int,
    val expiryEpoch: Long,
)
