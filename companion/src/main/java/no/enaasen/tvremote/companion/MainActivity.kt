package no.enaasen.tvremote.companion

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Says whether notification access is granted, and how to grant it. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 22f }
        val grant = Button(this).apply {
            text = "Open notification access"
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { status.text = MANUAL_GRANT }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(64, 64, 64, 64)
                addView(status)
                addView(grant)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val granted = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MediaListenerService::class.java))
        status.text = if (granted) {
            "Notification access is granted.\nServing what is playing on port ${NowPlayingServer.PORT}."
        } else {
            "Notification access is needed to read what is playing.\n\n$MANUAL_GRANT"
        }
    }

    private companion object {
        const val MANUAL_GRANT = "If the settings screen is missing on this TV, grant it over adb:\n" +
            "adb shell cmd notification allow_listener no.enaasen.tvremote.companion/.MediaListenerService"
    }
}
