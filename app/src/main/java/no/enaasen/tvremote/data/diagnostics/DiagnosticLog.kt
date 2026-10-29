package no.enaasen.tvremote.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.enaasen.tvremote.di.ApplicationScope
import org.json.JSONObject

enum class DiagnosticLevel { Info, Warning, Error }

enum class DiagnosticCategory { App, Connection, Pairing, Discovery, Service, Receiver }

data class DiagnosticEvent(
    val timeMillis: Long,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val message: String,
    val detail: String? = null,
)

/**
 * The app's own diagnostic history, so connection problems can be understood
 * from the phone without adb. Kept on the device only, capped at
 * [MAX_EVENTS]; crashes are written synchronously so they survive the crash.
 */
@Singleton
class DiagnosticLog @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "diagnostics.jsonl")
    private val lastExitFile = File(context.filesDir, "diagnostics_last_exit")
    private val pendingWrites = Channel<DiagnosticEvent>(Channel.UNLIMITED)

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    /** Oldest first. */
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val stored = synchronized(file) { readStored() }
            _events.update { (stored + it).takeLast(MAX_EVENTS) }
            for (event in pendingWrites) synchronized(file) { append(event) }
        }
    }

    fun info(category: DiagnosticCategory, message: String, detail: String? = null) =
        record(DiagnosticLevel.Info, category, message, detail)

    fun warning(category: DiagnosticCategory, message: String, error: Throwable? = null) =
        record(DiagnosticLevel.Warning, category, message, error?.describe())

    fun error(category: DiagnosticCategory, message: String, error: Throwable? = null) =
        record(DiagnosticLevel.Error, category, message, error?.describe())

    fun clear() {
        _events.value = emptyList()
        synchronized(file) { file.delete() }
    }

    /** Records uncaught exceptions before the process dies, then lets the default handler run. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val event = DiagnosticEvent(
                    timeMillis = System.currentTimeMillis(),
                    level = DiagnosticLevel.Error,
                    category = DiagnosticCategory.App,
                    message = "App crashed on thread ${thread.name}",
                    detail = throwable.describe(includeStack = true),
                )
                synchronized(file) { append(event) }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Logs why the previous process ended (killed for memory, ANR, …) — key to understanding dropped connections. */
    fun recordPreviousExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val lastSeen = runCatching { lastExitFile.readText().toLong() }.getOrDefault(0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        for (exit in exits) {
            // Java crashes are already recorded by the crash handler.
            if (exit.reason == ApplicationExitInfo.REASON_CRASH) continue
            val level = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> DiagnosticLevel.Error
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_USER_STOPPED,
                ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                ApplicationExitInfo.REASON_EXIT_SELF -> DiagnosticLevel.Info
                else -> DiagnosticLevel.Warning
            }
            record(
                event = DiagnosticEvent(
                    timeMillis = exit.timestamp,
                    level = level,
                    category = DiagnosticCategory.App,
                    message = "Previous run ended: ${exitReasonName(exit.reason)}",
                    detail = exit.description,
                ),
            )
        }
        exits.lastOrNull()?.let { runCatching { lastExitFile.writeText(it.timestamp.toString()) } }
    }

    private fun record(level: DiagnosticLevel, category: DiagnosticCategory, message: String, detail: String?) =
        record(DiagnosticEvent(System.currentTimeMillis(), level, category, message, detail))

    private fun record(event: DiagnosticEvent) {
        Log.println(
            when (event.level) {
                DiagnosticLevel.Info -> Log.INFO
                DiagnosticLevel.Warning -> Log.WARN
                DiagnosticLevel.Error -> Log.ERROR
            },
            "TvRemote/${event.category}",
            listOfNotNull(event.message, event.detail).joinToString(" — "),
        )
        _events.update { (it + event).takeLast(MAX_EVENTS) }
        pendingWrites.trySend(event)
    }

    private fun append(event: DiagnosticEvent) {
        file.appendText(event.toJson() + "\n")
        if (file.length() > TRIM_THRESHOLD_BYTES) {
            val kept = readStored().takeLast(MAX_EVENTS)
            file.writeText(kept.joinToString(separator = "\n", postfix = "\n") { it.toJson() })
        }
    }

    private fun readStored(): List<DiagnosticEvent> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line -> runCatching { line.toEvent() }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun DiagnosticEvent.toJson(): String = JSONObject()
        .put("t", timeMillis)
        .put("l", level.name)
        .put("c", category.name)
        .put("m", message)
        .apply { detail?.let { put("d", it) } }
        .toString()

    private fun String.toEvent(): DiagnosticEvent = JSONObject(this).let { json ->
        DiagnosticEvent(
            timeMillis = json.getLong("t"),
            level = DiagnosticLevel.valueOf(json.getString("l")),
            category = DiagnosticCategory.valueOf(json.getString("c")),
            message = json.getString("m"),
            detail = json.optString("d").takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val MAX_EVENTS = 1_000
        const val TRIM_THRESHOLD_BYTES = 512 * 1024L
        const val MAX_EXIT_REASONS = 10
    }
}

private fun Throwable.describe(includeStack: Boolean = false): String = buildString {
    append(this@describe::class.java.simpleName)
    message?.let { append(": ").append(it) }
    cause?.let { cause ->
        append("\nCaused by ").append(cause::class.java.simpleName)
        cause.message?.let { append(": ").append(it) }
    }
    if (includeStack) {
        stackTrace.take(12).forEach { append("\n  at ").append(it) }
    }
}

private fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
    ApplicationExitInfo.REASON_FREEZER -> "frozen by the system"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
    ApplicationExitInfo.REASON_OTHER -> "killed by the system"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
    ApplicationExitInfo.REASON_USER_STOPPED -> "force stopped"
    else -> "unknown ($reason)"
}
