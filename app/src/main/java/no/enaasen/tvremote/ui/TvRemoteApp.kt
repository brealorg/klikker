package no.enaasen.tvremote.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import no.enaasen.tvremote.ui.addtv.AddTvRoute as AddTvScreenRoute
import no.enaasen.tvremote.ui.diagnostics.DiagnosticsRoute as DiagnosticsScreenRoute
import no.enaasen.tvremote.ui.navigation.AddTvRoute
import no.enaasen.tvremote.ui.navigation.DiagnosticsRoute
import no.enaasen.tvremote.ui.navigation.PairingRoute
import no.enaasen.tvremote.ui.navigation.RemoteRoute
import no.enaasen.tvremote.ui.navigation.SettingsRoute
import no.enaasen.tvremote.ui.pairing.PairingRoute as PairingScreenRoute
import no.enaasen.tvremote.ui.remote.RemoteRoute as RemoteScreenRoute
import no.enaasen.tvremote.ui.settings.SettingsRoute as SettingsScreenRoute

@Composable
fun TvRemoteApp() {
    val backStack = rememberNavBackStack(RemoteRoute)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<RemoteRoute> {
                RemoteScreenRoute(
                    onAddTv = { backStack.add(AddTvRoute) },
                    onPairTv = { tv -> backStack.add(PairingRoute(tv.name, tv.host, tv.port)) },
                    onOpenSettings = { backStack.add(SettingsRoute) },
                    onOpenDiagnostics = { backStack.add(DiagnosticsRoute) },
                )
            }
            entry<SettingsRoute> {
                SettingsScreenRoute(
                    onBack = { backStack.removeLastOrNull() },
                    onOpenDiagnostics = { backStack.add(DiagnosticsRoute) },
                )
            }
            entry<DiagnosticsRoute> {
                DiagnosticsScreenRoute(onBack = { backStack.removeLastOrNull() })
            }
            entry<AddTvRoute> {
                AddTvScreenRoute(
                    onBack = { backStack.removeLastOrNull() },
                    onTvChosen = { name, host, port -> backStack.add(PairingRoute(name, host, port)) },
                )
            }
            entry<PairingRoute> { route ->
                PairingScreenRoute(
                    route = route,
                    onBack = { backStack.removeLastOrNull() },
                    onPaired = { backStack.retainAll { it == RemoteRoute } },
                )
            }
        },
    )
}
