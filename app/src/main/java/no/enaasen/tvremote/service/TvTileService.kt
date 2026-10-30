package no.enaasen.tvremote.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.volumeLabel

/** Shared by the quick settings tiles: they follow the TV's state while the panel is open. */
abstract class TvTileService : TileService() {

    @Inject
    lateinit var repository: TvRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        listening = scope.launch {
            combine(repository.connectionState, repository.receiverStatus) { connection, receiver ->
                (connection as? ConnectionState.Connected)?.status to receiver
            }.collect { (status, receiver) ->
                qsTile?.let { tile ->
                    update(tile, TvTileState(status?.isOn, status?.isMuted, receiver.isConnected, receiver.isMuted, receiver.volumeLabel(), status != null))
                    tile.updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    protected abstract fun update(tile: Tile, state: TvTileState)

    protected class TvTileState(
        val isOn: Boolean?,
        val tvMuted: Boolean?,
        val receiverConnected: Boolean,
        val receiverMuted: Boolean,
        val receiverVolume: String?,
        val tvConnected: Boolean,
    ) {
        /** The receiver's mute when it is controlled directly, otherwise the TV's. */
        val muted: Boolean get() = if (receiverConnected) receiverMuted else tvMuted == true
    }
}

/** Turns the TV on or off. */
@AndroidEntryPoint
class PowerTileService : TvTileService() {
    override fun onClick() = repository.togglePower()

    override fun update(tile: Tile, state: TvTileState) {
        tile.label = getString(R.string.tile_power)
        tile.subtitle = getString(
            when {
                !state.tvConnected -> R.string.status_connecting
                state.isOn == true -> R.string.tile_on
                else -> R.string.tile_off
            },
        )
        tile.state = when {
            !state.tvConnected -> Tile.STATE_UNAVAILABLE
            state.isOn == true -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
    }
}

/** Mutes the sound, and shows the volume while it isn't. */
@AndroidEntryPoint
class MuteTileService : TvTileService() {
    override fun onClick() = repository.sendKey(KeyEvent.KEYCODE_VOLUME_MUTE)

    override fun update(tile: Tile, state: TvTileState) {
        tile.label = getString(R.string.mute)
        tile.subtitle = if (state.muted) getString(R.string.tile_muted) else state.receiverVolume
        tile.state = when {
            !state.tvConnected && !state.receiverConnected -> Tile.STATE_UNAVAILABLE
            state.muted -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
    }
}
