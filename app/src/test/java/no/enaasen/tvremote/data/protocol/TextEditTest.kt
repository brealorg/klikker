package no.enaasen.tvremote.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditTest {
    @Test
    fun appendsTyping() = assertEquals(TextEdit(0, 3, "e"), textEdit("abc", "abce"))

    @Test
    fun deletesBackToTheChange() = assertEquals(TextEdit(2, 1, "x"), textEdit("abc", "ax"))

    @Test
    fun clears() = assertEquals(TextEdit(3, 0, ""), textEdit("abc", ""))

    @Test
    fun deletesAnEmojiWithOnePress() = assertEquals(TextEdit(1, 2, ""), textEdit("hi😀", "hi"))

    @Test
    fun replacesAWholeEmojiWhenOnlyItsSkinToneChanges() =
        assertEquals(TextEdit(1, 0, "👍"), textEdit("👍🏽", "👍"))

    @Test
    fun neverSplitsASurrogatePair() =
        // 😀 and 😁 share their high surrogate.
        assertEquals(TextEdit(1, 1, "😁"), textEdit("a😀", "a😁"))

    @Test
    fun deletesAFlagWithOnePress() = assertEquals(TextEdit(1, 1, ""), textEdit("a🇳🇴", "a"))
}
