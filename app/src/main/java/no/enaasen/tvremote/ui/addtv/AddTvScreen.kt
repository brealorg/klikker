package no.enaasen.tvremote.ui.addtv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.protocol.RemoteSession

@Composable
fun AddTvRoute(
    onBack: () -> Unit,
    onTvChosen: (name: String, host: String, port: Int) -> Unit,
    viewModel: AddTvViewModel = hiltViewModel(),
) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    AddTvScreen(discovered, onBack, onTvChosen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTvScreen(
    discovered: List<DiscoveredTvItem>,
    onBack: () -> Unit,
    onTvChosen: (name: String, host: String, port: Int) -> Unit,
) {
    var showIpDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_tv)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    text = stringResource(R.string.found_on_network),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (discovered.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.searching_for_tvs)) },
                        leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                    )
                }
            }
            items(discovered, key = { it.tv.name }) { item ->
                ListItem(
                    headlineContent = { Text(item.tv.name) },
                    supportingContent = {
                        Text(if (item.alreadyAdded) stringResource(R.string.already_added) else item.tv.host)
                    },
                    leadingContent = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
                    modifier = Modifier.clickable { onTvChosen(item.tv.name, item.tv.host, item.tv.port) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.enter_ip)) },
                    supportingContent = { Text(stringResource(R.string.enter_ip_hint)) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_lan_fill1), contentDescription = null) },
                    modifier = Modifier.clickable { showIpDialog = true },
                )
            }
        }
    }

    if (showIpDialog) {
        var ip by rememberSaveable { mutableStateOf("") }
        val submit = {
            showIpDialog = false
            onTvChosen(ip.trim(), ip.trim(), RemoteSession.DEFAULT_PORT)
        }
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text(stringResource(R.string.enter_ip)) },
            text = {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.ip_address)) },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (ip.isNotBlank()) submit() }),
                )
            },
            confirmButton = {
                TextButton(onClick = submit, enabled = ip.isNotBlank()) { Text(stringResource(R.string.next)) }
            },
            dismissButton = {
                TextButton(onClick = { showIpDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
