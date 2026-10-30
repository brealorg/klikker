package no.enaasen.tvremote.ui.remote

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.model.TvTextField

/**
 * Types into the text field focused on the TV with the phone's own keyboard,
 * set up like the TV's field (keyboard type, action key), above the keyboard.
 */
@Composable
internal fun TvTextInputBar(
    field: TvTextField,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHide: () -> Unit,
) {
    // Starts from the TV's text; afterwards the phone leads, so the TV's
    // reports don't fight the user's typing.
    var value by rememberSaveable(field.focusId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(field.text, TextRange(field.text.length)))
    }
    // Every text sent to the TV in this field: its echoes and the steps it passes
    // through while deleting are ours, anything else was changed on the TV.
    val sent = remember(field.focusId) { mutableSetOf(field.text) }
    LaunchedEffect(field.focusId, field.text) {
        delay(TV_EDIT_SETTLE_MS)
        if (field.text != value.text && field.text !in sent) {
            value = TextFieldValue(field.text, TextRange(field.text.length))
        }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(field.focusId) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val password = field.isPassword

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { new ->
                    if (new.text != value.text) {
                        sent += new.text
                        onTextChange(new.text)
                    }
                    value = new
                },
                label = { Text(field.label.ifBlank { stringResource(R.string.type_on_tv) }) },
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = field.keyboardOptions(),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(onClick = onHide) {
                Icon(painterResource(R.drawable.ic_keyboard_arrow_down), stringResource(R.string.hide_keyboard))
            }
        }
    }
}

/** Waits out the TV's own reports of our typing before taking its text as an edit made on the TV. */
private const val TV_EDIT_SETTLE_MS = 500L

internal val TvTextField.isPassword: Boolean
    get() {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

/** The phone keyboard set up as the TV field asks: type, capitals, correction and action key. */
private fun TvTextField.keyboardOptions(): KeyboardOptions {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val flags = inputType and InputType.TYPE_MASK_FLAGS
    val keyboardType = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> if (isPassword) KeyboardType.NumberPassword else KeyboardType.Number
        InputType.TYPE_CLASS_PHONE -> KeyboardType.Phone
        InputType.TYPE_CLASS_TEXT -> when {
            isPassword -> KeyboardType.Password
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> KeyboardType.Email
            variation == InputType.TYPE_TEXT_VARIATION_URI -> KeyboardType.Uri
            else -> KeyboardType.Text
        }
        else -> KeyboardType.Text
    }
    val capitalization = when {
        flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> KeyboardCapitalization.Characters
        flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> KeyboardCapitalization.Words
        flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> KeyboardCapitalization.Sentences
        else -> KeyboardCapitalization.None
    }
    val imeAction = when (imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_GO -> ImeAction.Go
        EditorInfo.IME_ACTION_SEARCH -> ImeAction.Search
        EditorInfo.IME_ACTION_SEND -> ImeAction.Send
        EditorInfo.IME_ACTION_NEXT -> ImeAction.Next
        EditorInfo.IME_ACTION_DONE -> ImeAction.Done
        EditorInfo.IME_ACTION_PREVIOUS -> ImeAction.Previous
        else -> ImeAction.Default
    }
    return KeyboardOptions(
        capitalization = capitalization,
        autoCorrectEnabled = flags and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0,
        keyboardType = keyboardType,
        imeAction = imeAction,
    )
}
