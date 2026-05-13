package com.atakmap.android.pluginmeshtastic.meshtastic

/**
 * Lockdown authentication state for a MESHTASTIC_LOCKDOWN-hardened device.
 *
 * Driven by FromRadio.lockdown_status (typed proto wire format from
 * meshtastic/protobufs PR #911). The UI subscribes to a flow of these.
 */
sealed class LockdownState {
    /** No lockdown signal received yet (default for non-hardened firmware). */
    object None : LockdownState()

    /** First-boot device with no passphrase set — prompt operator to pick one. */
    object NeedsProvision : LockdownState()

    /**
     * Storage is locked or this connection hasn't authed yet.
     * [reason] is the firmware-supplied `lock_reason` string (machine-readable);
     * unknown reasons are still treated as Locked.
     */
    data class Locked(val reason: String) : LockdownState()

    /**
     * Session authorized. [bootsRemaining] and [validUntilEpoch] mirror the firmware's
     * session token TTL (epoch=0 means no wall-clock expiry).
     */
    data class Unlocked(val bootsRemaining: Int, val validUntilEpoch: Long) : LockdownState()

    /** Wrong passphrase, no rate-limit — retry allowed immediately. */
    object UnlockFailed : LockdownState()

    /** Rate-limited — caller must wait [backoffSeconds] before retrying. */
    data class UnlockBackoff(val backoffSeconds: Int) : LockdownState()

    /**
     * Synthetic state — set when the coordinator's pending Lock Now flag is resolved
     * by the next inbound LOCKED status (or a connection drop). UI should disconnect.
     */
    object LockNowAcknowledged : LockdownState()
}
