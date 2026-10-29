package no.enaasen.tvremote.data.diagnostics

/** "45 s", "12 min 3 s", "2 h 5 min". */
fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    }
}
