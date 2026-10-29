package no.enaasen.tvremote.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object RemoteRoute : NavKey

@Serializable
data object AddTvRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object DiagnosticsRoute : NavKey

@Serializable
data class PairingRoute(val name: String, val host: String, val port: Int) : NavKey
