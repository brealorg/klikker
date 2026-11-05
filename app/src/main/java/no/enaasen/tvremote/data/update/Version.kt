package no.enaasen.tvremote.data.update

import kotlin.math.max

/** Whether release tag [latest] (like "v1.0.3") is a higher version than [current] (like "1.0.2"). */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(version: String): List<Int>? =
        version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

    val a = parts(latest) ?: return false
    val b = parts(current) ?: return false
    for (i in 0 until max(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
