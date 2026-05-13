package com.atakmap.android.pluginmeshtastic.meshtastic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class StoredPassphrase(
    val passphrase: String,
    val boots: Int,
    val hours: Int,
)

/**
 * Per-device encrypted storage for lockdown passphrases.
 *
 * Backed by EncryptedSharedPreferences with an AES-256-GCM master key from the Android
 * Keystore. The key is intentionally not biometric-gated so silent auto-unlock can run
 * after a reconnect without user interaction.
 *
 * Keyed by BLE MAC address (or USB device path) so a plugin instance connecting to a
 * second device does not inherit the first device's cached passphrase.
 */
class LockdownPassphraseStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        try {
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // EncryptedSharedPreferences can fail if the underlying keyset file is corrupted
            // (e.g. after a backup/restore). Wipe and recreate rather than crash the plugin.
            Log.w(TAG, "Failed to open EncryptedSharedPreferences, wiping and retrying", e)
            context.applicationContext.deleteSharedPreferences(PREFS_FILE_NAME)
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    fun getPassphrase(deviceAddress: String): StoredPassphrase? {
        val key = sanitizeKey(deviceAddress)
        val passphrase = prefs.getString("${key}_passphrase", null) ?: return null
        val boots = prefs.getInt("${key}_boots", DEFAULT_BOOTS)
        val hours = prefs.getInt("${key}_hours", 0)
        return StoredPassphrase(passphrase, boots, hours)
    }

    fun savePassphrase(deviceAddress: String, passphrase: String, boots: Int, hours: Int) {
        val key = sanitizeKey(deviceAddress)
        prefs.edit()
            .putString("${key}_passphrase", passphrase)
            .putInt("${key}_boots", boots)
            .putInt("${key}_hours", hours)
            .apply()
    }

    fun clearPassphrase(deviceAddress: String) {
        val key = sanitizeKey(deviceAddress)
        prefs.edit()
            .remove("${key}_passphrase")
            .remove("${key}_boots")
            .remove("${key}_hours")
            .apply()
    }

    private fun sanitizeKey(address: String): String = address.replace(":", "_").replace('/', '_')

    companion object {
        private const val TAG = "LockdownPassphraseStore"
        private const val PREFS_FILE_NAME = "lockdown_passphrase_store"
        const val DEFAULT_BOOTS = 50
    }
}
