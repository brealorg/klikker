package no.enaasen.tvremote.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import no.enaasen.tvremote.R

/**
 * Android 17 requires a runtime grant before apps can talk to devices on the
 * local network. Shows a rationale until it's granted.
 */
@Composable
fun LocalNetworkPermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (Build.VERSION.SDK_INT < ANDROID_17) {
        content()
    } else {
        PermissionGate(onGranted, content)
    }
}

@RequiresApi(ANDROID_17)
@Composable
private fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasLocalNetworkPermission()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
        denied = !isGranted
        if (isGranted) onGranted()
    }
    LifecycleResumeEffect(Unit) {
        val nowGranted = context.hasLocalNetworkPermission()
        if (nowGranted && !granted) onGranted()
        granted = nowGranted
        onPauseOrDispose { }
    }

    if (granted) {
        content()
    } else {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lan_fill1),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.permission_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) {
                    Text(stringResource(R.string.permission_allow))
                }
                if (denied) {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.permission_open_settings))
                    }
                }
            }
        }
    }
}

private const val ANDROID_17 = 37

@RequiresApi(ANDROID_17)
private fun Context.hasLocalNetworkPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
