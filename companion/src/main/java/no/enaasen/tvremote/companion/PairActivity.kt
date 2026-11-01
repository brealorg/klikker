package no.enaasen.tvremote.companion

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Opened by a link from the phone app, `tvremote-companion://pair?secret=…`,
 * which the phone sends through the TV's remote protocol. It shows nothing:
 * it stores the secret and closes.
 */
class PairActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secret = intent?.data?.getQueryParameter("secret")
        if (PairingStore.isValid(secret)) {
            PairingStore.add(this, secret!!)
            Log.i("PairActivity", "A phone was paired")
        } else {
            Log.w("PairActivity", "Ignored a pairing link without a valid secret")
        }
        finish()
    }
}
