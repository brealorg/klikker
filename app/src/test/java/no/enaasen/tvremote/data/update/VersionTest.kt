package no.enaasen.tvremote.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun higherVersionsAreNewer() {
        assertTrue(isNewerVersion("v1.0.3", "1.0.2"))
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(isNewerVersion("v2.0.0", "1.9.9"))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(isNewerVersion("v1.0.10", "1.0.9"))
        assertFalse(isNewerVersion("v1.0.9", "1.0.10"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(isNewerVersion("v1.0.2", "1.0.2"))
        assertFalse(isNewerVersion("v1.0.1", "1.0.2"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertFalse(isNewerVersion("v1.0", "1.0.0"))
        assertTrue(isNewerVersion("v1.0.1", "1.0"))
    }

    @Test
    fun unreadableVersionsAreNeverNewer() {
        assertFalse(isNewerVersion("latest", "1.0.2"))
        assertFalse(isNewerVersion("v1.0.3", "unknown"))
        assertFalse(isNewerVersion("", "1.0.2"))
    }
}
