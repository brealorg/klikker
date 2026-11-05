package no.enaasen.tvremote.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.di.ApplicationScope
import org.json.JSONException
import org.json.JSONObject

data class ReleaseInfo(
    val version: String,
    val apkUrl: String,
    /** Lower-case hex, from GitHub's record of the uploaded file; null if it doesn't give one. */
    val sha256: String?,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState

    /** Handed over to Android's installer, which asks the user to confirm. */
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val release: ReleaseInfo) : UpdateState
}

/**
 * Tells the user when a newer release is out on GitHub, and installs it from
 * there on request, so nobody has to go to GitHub to update. Android's installer
 * only accepts an update signed with the same key as the installed app, and the
 * download is also checked against the checksum GitHub has for the file.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: DiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val currentVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    // Debug builds are a separate app, and installing the release over them isn't an update.
    private val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @Volatile
    private var lastCheckAt = 0L

    /** Looks for a newer release, at most once an hour. */
    suspend fun check() {
        val current = currentVersion ?: return
        if (isDebuggable || _state.value != UpdateState.None) return
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        val release = try {
            fetchLatest()
        } catch (e: IOException) {
            log.warning(DiagnosticCategory.App, "Could not check for a newer version", e)
            return
        }
        if (release == null || !isNewerVersion(release.version, current)) return
        if (release.version == prefs.getString(DISMISSED_VERSION, null)) return
        log.info(DiagnosticCategory.App, "Version ${release.version} is available (running $current)")
        _state.value = UpdateState.Available(release)
    }

    /** Hides the offer for this version; the next release is offered again. */
    fun dismiss() {
        val release = releaseOf(_state.value) ?: return
        prefs.edit().putString(DISMISSED_VERSION, release.version).apply()
        _state.value = UpdateState.None
    }

    /** Downloads the offered release and hands it to Android's installer. */
    fun update() {
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> current.release
            else -> return
        }
        _state.value = UpdateState.Downloading(release, 0)
        scope.launch {
            try {
                val apk = download(release)
                _state.value = UpdateState.Installing(release)
                install(apk)
            } catch (e: IOException) {
                log.error(DiagnosticCategory.App, "Updating to ${release.version} failed", e)
                _state.value = UpdateState.Failed(release)
            } catch (e: SecurityException) {
                log.error(DiagnosticCategory.App, "Updating to ${release.version} was not allowed", e)
                _state.value = UpdateState.Failed(release)
            }
        }
    }

    /** Android's installer reported back, unless it succeeded, which replaces this process. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = releaseOf(_state.value) ?: return
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            // The user said no in Android's dialog; the offer stays.
            log.info(DiagnosticCategory.App, "Installing ${release.version} was cancelled")
            _state.value = UpdateState.Available(release)
        } else {
            log.error(DiagnosticCategory.App, "Android could not install ${release.version}: ${message ?: "no reason given"}")
            _state.value = UpdateState.Failed(release)
        }
    }

    private fun releaseOf(state: UpdateState): ReleaseInfo? = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
        UpdateState.None -> null
    }

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = open(LATEST_RELEASE_URL).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub answered ${connection.responseCode}")
            }
            parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: String): ReleaseInfo? = try {
        val release = JSONObject(json)
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
        apk?.let {
            ReleaseInfo(
                version = release.getString("tag_name").removePrefix("v"),
                apkUrl = it.getString("browser_download_url"),
                sha256 = it.optString("digest").removePrefix("sha256:").takeIf { digest -> digest.length == 64 }?.lowercase(),
                sizeBytes = it.optLong("size"),
            )
        }
    } catch (e: JSONException) {
        throw IOException("Unexpected reply from GitHub", e)
    }

    private suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, "Klikker-${release.version}.apk")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download answered ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var shown = 0
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        val percent = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && actual != release.sha256) {
                file.delete()
                throw IOException("The download did not match its checksum")
            }
            file
        } finally {
            connection.disconnect()
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("klikker.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val report = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                report,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", "Klikker")
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/brealorg/klikker/releases/latest"
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        const val TIMEOUT_MS = 10_000
        const val DISMISSED_VERSION = "dismissed_version"
    }
}
