package no.enaasen.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import no.enaasen.tvremote.data.TvRepository

/** Debug builds only: `adb shell am broadcast -a no.enaasen.tvremote.DEBUG_TEXT --es text hello`. */
@AndroidEntryPoint
class DebugTextReceiver : BroadcastReceiver() {
    @Inject
    lateinit var repository: TvRepository

    override fun onReceive(context: Context, intent: Intent) {
        repository.setText(intent.getStringExtra("text").orEmpty())
    }
}
