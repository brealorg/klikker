package no.enaasen.tvremote.data.nowplaying

import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSelectionTest {
    private fun session(pkg: String, state: MediaState, title: String? = "Title") =
        MediaSessionInfo(pkg, state, title, subtitle = null, positionMs = null, durationMs = null)

    @Test
    fun onlyTheAppsOwnSessionsCount() {
        val sessions = listOf(session("a", MediaState.Playing), session("b", MediaState.Playing))
        assertEquals("a", sessions.forApp("a")?.packageName)
        assertNull(sessions.forApp("c"))
    }

    @Test
    fun aPlayingSessionBeatsAPausedOne() {
        val paused = session("a", MediaState.Paused, "Old")
        val playing = session("a", MediaState.Playing, "New")
        assertEquals("New", listOf(paused, playing).forApp("a")?.title)
    }

    @Test
    fun stoppedAndUntitledSessionsAreSkipped() {
        val sessions = listOf(session("a", MediaState.Stopped), session("a", MediaState.Paused, title = null))
        assertNull(sessions.forApp("a"))
        assertEquals("Kept", (sessions + session("a", MediaState.Paused, "Kept")).forApp("a")?.title)
    }
}
