package no.enaasen.tvremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.proto.remote.RemoteDirection
import no.enaasen.tvremote.service.RemoteConnectionService
import no.enaasen.tvremote.ui.TvRemoteApp
import no.enaasen.tvremote.ui.permission.LocalNetworkPermissionGate
import no.enaasen.tvremote.ui.theme.TvRemoteTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: TvRepository

    // The connection service runs without it, but its controls live in the notification.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Foreground services may only be started while the app is visible.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.backgroundConnectionWanted.collect { wanted ->
                    if (wanted) {
                        RemoteConnectionService.start(this@MainActivity)
                        requestNotificationPermissionOnce(savedInstanceState)
                    }
                }
            }
        }
        setContent {
            TvRemoteTheme {
                LocalNetworkPermissionGate(onGranted = repository::reconnect) {
                    TvRemoteApp()
                }
            }
        }
    }

    private var askedForNotifications = false

    private fun requestNotificationPermissionOnce(savedInstanceState: Bundle?) {
        if (askedForNotifications || savedInstanceState != null) return
        askedForNotifications = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val volumeKeysControlTv: Boolean
        get() = repository.isConnected && repository.settings.value.volumeKeysControlTv

    private var volumeKeyHeld = false

    // While connected, the phone's volume buttons control the TV instead. A tap
    // is sent on release; holding sends a long press the TV repeats itself.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode.isVolumeKey() && volumeKeysControlTv) {
            if (event.repeatCount == 1) {
                volumeKeyHeld = true
                repository.sendKey(keyCode, RemoteDirection.START_LONG)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode.isVolumeKey() && volumeKeyHeld) {
            volumeKeyHeld = false
            repository.sendKey(keyCode, RemoteDirection.END_LONG)
            return true
        }
        if (keyCode.isVolumeKey() && volumeKeysControlTv) {
            repository.sendKey(keyCode)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun Int.isVolumeKey() = this == KeyEvent.KEYCODE_VOLUME_UP || this == KeyEvent.KEYCODE_VOLUME_DOWN
}
