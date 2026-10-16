package dev.javieraguilera.mysone.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** [denied]: se pidió y se negó (el sistema puede no volver a preguntar; queda abrir sus ajustes). */
class PermissionState(val granted: Boolean, val denied: Boolean, val request: () -> Unit)

/** Estado de un permiso en tiempo de ejecución; se vuelve a comprobar al volver a la app. */
@Composable
fun rememberPermissionState(permission: String, onResult: (Boolean) -> Unit = {}): PermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(isGranted(context, permission)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
        onResult(it)
    }
    LifecycleResumeEffect(permission) {
        granted = isGranted(context, permission)
        onPauseOrDispose {}
    }
    return PermissionState(granted, asked && !granted) { launcher.launch(permission) }
}

fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Ficha de la app en los ajustes del sistema, para dar un permiso que ya se negó. */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
