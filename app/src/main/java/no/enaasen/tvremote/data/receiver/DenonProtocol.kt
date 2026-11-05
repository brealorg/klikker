package no.enaasen.tvremote.data.receiver

import kotlin.math.roundToInt

/**
 * Text protocol spoken by Denon and Marantz receivers on TCP port 23. Volume
 * is on a 0–98 scale in 0.5 steps ("MV38" is 38, "MV375" is 37.5); here it is
 * kept as whole half-steps so 37.5 is 75.
 */
internal object DenonProtocol {
    const val PORT = 23

    /** "MV99" means the minimum ("---"), below "MV00". */
    private const val MINIMUM = "99"

    /** Parses the digits after "MV" or "MVMAX ", or null if they aren't a volume. */
    fun parseVolume(digits: String): Int? = when {
        digits == MINIMUM -> 0
        digits.length == 2 -> digits.toIntOrNull()?.times(2)
        digits.length == 3 && digits.endsWith('5') -> digits.take(2).toIntOrNull()?.let { it * 2 + 1 }
        else -> null
    }

    fun setVolumeCommand(halfSteps: Int): String {
        val whole = (halfSteps / 2).toString().padStart(2, '0')
        return if (halfSteps % 2 == 0) "MV$whole" else "MV${whole}5"
    }

    /** The next volume [step] half-steps away, snapped to multiples of [step]. */
    fun stepVolume(current: Int, step: Int, up: Boolean, max: Int): Int {
        val next = if (up) (current / step + 1) * step else ((current + step - 1) / step - 1) * step
        return next.coerceIn(0, max)
    }

    const val HTTP_PORT = 80
    const val HTTP_STATUS_PATH = "/goform/formMainZone_MainZoneXml.xml"

    /** The same command strings as on port 23, sent as a query to the receiver's web interface. */
    fun httpCommandPath(command: String) = "/goform/formiPhoneAppDirect.xml?$command"

    private val httpVolume = Regex("<MasterVolume>\\s*<value>([^<]*)</value>")
    private val httpVolumeDisplay = Regex("<VolumeDisplay>\\s*<value>([^<]*)</value>")
    private val httpMute = Regex("<Mute>\\s*<value>([^<]*)</value>")

    /**
     * Volume in half-steps from the web interface's status XML, or null if it has none.
     * The web interface shows dB relative to 80 ("-40.0" is 40) unless it is set to absolute.
     */
    fun parseHttpVolume(xml: String): Int? {
        val raw = httpVolume.find(xml)?.groupValues?.get(1)?.trim() ?: return null
        if (raw.startsWith("--")) return 0
        val value = raw.toDoubleOrNull() ?: return null
        val absolute = httpVolumeDisplay.find(xml)?.groupValues?.get(1)?.trim().equals("Absolute", ignoreCase = true)
        return ((if (absolute) value else value + 80) * 2).roundToInt().coerceIn(0, 196)
    }

    /** Mute state from the web interface's status XML, or null if it has none. */
    fun parseHttpMute(xml: String): Boolean? =
        when (httpMute.find(xml)?.groupValues?.get(1)?.trim()?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }
}
