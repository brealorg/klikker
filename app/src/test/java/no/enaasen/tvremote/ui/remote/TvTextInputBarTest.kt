package no.enaasen.tvremote.ui.remote

import android.text.InputType
import no.enaasen.tvremote.data.model.TvTextField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvTextInputBarTest {
    private fun field(inputType: Int) = TvTextField(focusId = 1, label = "", text = "", inputType = inputType, imeOptions = 0)

    @Test
    fun textPasswordsAreHidden() {
        assertTrue(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).isPassword)
        assertTrue(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD).isPassword)
        assertTrue(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE).isPassword)
    }

    @Test
    fun numberPinsAreHidden() =
        assertTrue(field(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).isPassword)

    @Test
    fun ordinaryFieldsAreShown() {
        assertFalse(field(InputType.TYPE_CLASS_TEXT).isPassword)
        assertFalse(field(65537).isPassword) // SmartTube's search field
        assertFalse(field(InputType.TYPE_CLASS_NUMBER).isPassword)
        assertFalse(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).isPassword)
    }
}
