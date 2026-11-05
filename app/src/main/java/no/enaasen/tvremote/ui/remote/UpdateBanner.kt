package no.enaasen.tvremote.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.update.UpdateState

/** Offers a newer version of the app, and shows how installing it is going. */
@Composable
internal fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    if (state == UpdateState.None) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is UpdateState.Available -> {
                    Text(stringResource(R.string.update_available, state.release.version), style = MaterialTheme.typography.titleMedium)
                    Buttons(R.string.update_action, onUpdate, onDismiss)
                }
                is UpdateState.Downloading -> {
                    Text(stringResource(R.string.update_downloading, state.release.version), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> {
                    Text(stringResource(R.string.update_installing, state.release.version), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Failed -> {
                    Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.titleMedium)
                    Buttons(R.string.update_retry, onUpdate, onDismiss)
                }
                UpdateState.None -> Unit
            }
        }
    }
}

@Composable
private fun Buttons(actionLabel: Int, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onUpdate) { Text(stringResource(actionLabel)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_dismiss)) }
    }
}
