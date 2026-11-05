package no.enaasen.tvremote.data.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DenonProtocolTest {

    @Test
    fun parsesWholeAndHalfVolumes() {
        assertEquals(76, DenonProtocol.parseVolume("38"))
        assertEquals(75, DenonProtocol.parseVolume("375"))
        assertEquals(1, DenonProtocol.parseVolume("005"))
        assertEquals(163, DenonProtocol.parseVolume("815"))
    }

    @Test
    fun parsesMinimumAsZero() {
        assertEquals(0, DenonProtocol.parseVolume("99"))
    }

    @Test
    fun rejectsOtherReplies() {
        assertNull(DenonProtocol.parseVolume(""))
        assertNull(DenonProtocol.parseVolume("3"))
        assertNull(DenonProtocol.parseVolume("374"))
        assertNull(DenonProtocol.parseVolume("AX"))
    }

    @Test
    fun formatsSetVolumeCommands() {
        assertEquals("MV38", DenonProtocol.setVolumeCommand(76))
        assertEquals("MV375", DenonProtocol.setVolumeCommand(75))
        assertEquals("MV05", DenonProtocol.setVolumeCommand(10))
        assertEquals("MV005", DenonProtocol.setVolumeCommand(1))
    }

    @Test
    fun stepsSnapToTheStepSize() {
        // 37.5 with 1 dB steps goes to 38 / 37, not 38.5 / 36.5.
        assertEquals(76, DenonProtocol.stepVolume(75, step = 2, up = true, max = 196))
        assertEquals(74, DenonProtocol.stepVolume(75, step = 2, up = false, max = 196))
        assertEquals(78, DenonProtocol.stepVolume(76, step = 2, up = true, max = 196))
        assertEquals(77, DenonProtocol.stepVolume(76, step = 1, up = true, max = 196))
        assertEquals(80, DenonProtocol.stepVolume(76, step = 4, up = true, max = 196))
    }

    @Test
    fun stepsStayWithinRange() {
        assertEquals(163, DenonProtocol.stepVolume(163, step = 2, up = true, max = 163))
        assertEquals(0, DenonProtocol.stepVolume(1, step = 2, up = false, max = 196))
        assertEquals(0, DenonProtocol.stepVolume(0, step = 2, up = false, max = 196))
    }

    private fun status(volume: String, display: String = "Relative", mute: String = "off") =
        "<item><VolumeDisplay><value>$display</value></VolumeDisplay>" +
            "<MasterVolume><value>$volume</value></MasterVolume><Mute><value>$mute</value></Mute></item>"

    @Test
    fun parsesRelativeHttpVolume() {
        assertEquals(80, DenonProtocol.parseHttpVolume(status("-40.0")))
        assertEquals(75, DenonProtocol.parseHttpVolume(status("-42.5")))
        assertEquals(160, DenonProtocol.parseHttpVolume(status("0.0")))
    }

    @Test
    fun parsesAbsoluteHttpVolume() {
        assertEquals(76, DenonProtocol.parseHttpVolume(status("38.0", display = "Absolute")))
    }

    @Test
    fun parsesMinimumHttpVolumeAsZero() {
        assertEquals(0, DenonProtocol.parseHttpVolume(status("--")))
        assertEquals(0, DenonProtocol.parseHttpVolume(status("---.-")))
    }

    @Test
    fun rejectsHttpStatusWithoutVolume() {
        assertNull(DenonProtocol.parseHttpVolume("<item></item>"))
        assertNull(DenonProtocol.parseHttpVolume(status("loud")))
    }

    @Test
    fun parsesHttpMute() {
        assertEquals(false, DenonProtocol.parseHttpMute(status("-40.0", mute = "off")))
        assertEquals(true, DenonProtocol.parseHttpMute(status("-40.0", mute = "on")))
        assertNull(DenonProtocol.parseHttpMute("<item></item>"))
    }

    @Test
    fun buildsHttpCommandPaths() {
        assertEquals("/goform/formiPhoneAppDirect.xml?MV38", DenonProtocol.httpCommandPath("MV38"))
    }
}
