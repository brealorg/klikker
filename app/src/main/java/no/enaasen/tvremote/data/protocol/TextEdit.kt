package no.enaasen.tvremote.data.protocol

import java.text.BreakIterator

/**
 * Turns the TV's text into the wanted text: [backspaces] presses, which leave
 * the first [kept] chars, then [insert] typed at the end.
 */
internal data class TextEdit(val backspaces: Int, val kept: Int, val insert: String)

/**
 * The edit from [current] to [wanted]. A backspace deletes a whole character
 * as the user sees it (an emoji with its skin tone, a flag), so the kept part
 * ends on such a boundary and the presses are counted in them.
 */
internal fun textEdit(current: String, wanted: String): TextEdit {
    val common = current.commonPrefixWith(wanted).length
    val characters = BreakIterator.getCharacterInstance().apply { setText(current) }
    val kept = if (characters.isBoundary(common)) common else characters.preceding(common)
    characters.setText(current.substring(kept))
    var backspaces = 0
    while (characters.next() != BreakIterator.DONE) backspaces++
    return TextEdit(backspaces, kept, wanted.substring(kept))
}
