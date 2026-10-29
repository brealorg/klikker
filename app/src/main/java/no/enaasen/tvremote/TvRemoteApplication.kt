package no.enaasen.tvremote

import android.app.Application
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog

@HiltAndroidApp
class TvRemoteApplication : Application() {

    @Inject
    lateinit var log: DiagnosticLog

    override fun onCreate() {
        super.onCreate()
        log.installCrashHandler()
        log.recordPreviousExits()
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        log.info(
            DiagnosticCategory.App,
            "App started (version $version)",
            detail = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }
}
