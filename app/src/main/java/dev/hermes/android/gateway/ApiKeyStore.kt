package dev.hermes.android.gateway

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Provider API keys. Values are encrypted; the master key lives in the Android Keystore. */
object ApiKeyStore {
    private const val FILE = "api_keys"
    const val DEEPSEEK = "DEEPSEEK_API_KEY"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(context: Context, name: String): String = prefs(context).getString(name, "").orEmpty()

    fun save(context: Context, name: String, value: String) {
        prefs(context).edit().putString(name, value.trim()).apply()
    }
}
