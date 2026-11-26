package org.stypox.dicio.io.local_ai

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dicio's pairing with the SmolChat server: its token and base URL. Kept in a preferences file of
 * its own, which backups leave out (`data_extraction_rules.xml`): the contract asks clients to keep
 * the token out of backups that leave the phone, so after a restore Dicio pairs again.
 */
@Singleton
class LocalAiPairing @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class Pairing(val token: String, val baseUrl: String)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _pairing: MutableStateFlow<Pairing?> by lazy { MutableStateFlow(read()) }
    val pairing: StateFlow<Pairing?> get() = _pairing

    private fun read(): Pairing? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        return Pairing(token, baseUrl)
    }

    /** Stores the result of the pairing activity; returns whether it held a pairing. */
    fun onPairingResult(data: Intent?): Boolean {
        val token = data?.getStringExtra("token")?.takeIf { it.isNotBlank() } ?: return false
        val baseUrl = data.getStringExtra("base_url")?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: return false
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_BASE_URL, baseUrl).apply()
        _pairing.value = Pairing(token, baseUrl)
        return true
    }

    fun unpair() {
        prefs.edit().clear().apply()
        _pairing.value = null
    }

    fun isServerInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(LocalAiProtocol.SERVER_PACKAGE) != null

    companion object {
        /** Also named in data_extraction_rules.xml and full_backup_content.xml. */
        const val PREFS_NAME = "local_ai_pairing"
        private const val KEY_TOKEN = "token"
        private const val KEY_BASE_URL = "base_url"

        fun pairIntent(): Intent = Intent(LocalAiProtocol.ACTION_PAIR)
            .setPackage(LocalAiProtocol.SERVER_PACKAGE)
            .putExtra("client_name", "Dicio")
    }
}
