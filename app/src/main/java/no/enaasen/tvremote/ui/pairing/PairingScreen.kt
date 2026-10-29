package no.enaasen.tvremote.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.protocol.PairingSession
import no.enaasen.tvremote.ui.navigation.PairingRoute as PairingKey

@Composable
fun PairingRoute(
    route: PairingKey,
    onBack: () -> Unit,
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel<PairingViewModel, PairingViewModel.Factory>(
        creationCallback = { factory -> factory.create(route) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(uiState) {
        when (uiState) {
            PairingUiState.Paired -> {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onPaired()
            }
            is PairingUiState.EnterCode -> if ((uiState as PairingUiState.EnterCode).wrongCode) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            }
            else -> Unit
        }
    }
    PairingScreen(
        tvName = route.name,
        uiState = uiState,
        onBack = onBack,
        onSubmit = viewModel::submitCode,
        onRetry = viewModel::start,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingScreen(
    tvName: String,
    uiState: PairingUiState,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title, tvName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tv_fill1),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            when (uiState) {
                PairingUiState.Connecting, PairingUiState.Paired -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.pairing_connecting), style = MaterialTheme.typography.bodyLarge)
                }
                is PairingUiState.EnterCode -> CodeEntry(uiState, onSubmit)
                PairingUiState.Failed -> {
                    Text(
                        text = stringResource(R.string.pairing_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                }
            }
        }
    }
}

@Composable
private fun CodeEntry(state: PairingUiState.EnterCode, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val complete = code.length == PairingSession.CODE_LENGTH
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state.wrongCode) { if (state.wrongCode) code = "" }

    Text(
        text = stringResource(R.string.pairing_enter_code),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { input ->
            code = input.uppercase().filter { it in HEX_DIGITS }.take(PairingSession.CODE_LENGTH)
            if (code.length == PairingSession.CODE_LENGTH) onSubmit(code)
        },
        label = { Text(stringResource(R.string.pairing_code)) },
        isError = state.wrongCode,
        supportingText = if (state.wrongCode) {
            { Text(stringResource(R.string.pairing_wrong_code)) }
        } else {
            null
        },
        enabled = !state.verifying,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 8.sp,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (complete) onSubmit(code) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
    if (state.verifying) {
        CircularProgressIndicator()
    } else {
        Button(onClick = { onSubmit(code) }, enabled = complete) { Text(stringResource(R.string.pair)) }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
