package no.enaasen.tvremote.companion

import android.content.Context
import java.security.MessageDigest

/**
 * The secrets of the phones allowed to talk to this app. A phone adds its own
 * by opening a pairing link on the TV through the TV's remote protocol, which
 * only a phone paired with the TV can do. After that it sends the secret with
 * every request.
 */
internal object PairingStore {
    private const val PREFS = "pairing"
    private const val KEY = "secrets"
    private const val MAX_PHONES = 8
    private val VALID = Regex("[0-9a-f]{32}")

    fun isValid(secret: String?): Boolean = secret != null && VALID.matches(secret)

    /** Remembers [secret], dropping the oldest when there are too many. */
    @Synchronized
    fun add(context: Context, secret: String) {
        if (!isValid(secret)) return
        val secrets = (read(context) - secret + secret).takeLast(MAX_PHONES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, secrets.joinToString(",")).apply()
    }

    @Synchronized
    fun accepts(context: Context, token: String?): Boolean {
        if (token == null) return false
        val wanted = token.toByteArray()
        // Every secret is compared, whatever matches, so timing says nothing about them.
        return read(context).fold(false) { found, secret -> MessageDigest.isEqual(secret.toByteArray(), wanted) or found }
    }

    @Synchronized
    fun count(context: Context): Int = read(context).size

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(",")?.filter(::isValid).orEmpty()
}
