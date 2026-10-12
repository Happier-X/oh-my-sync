package dev.ohmysync.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ohmysync.MainViewModel

/** 首次打开时申请通知权限，不检查账户或同步文件夹。 */
@Composable
internal fun StartupAccess(model: MainViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { context.getSharedPreferences("startup-access", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) model.notify("通知未开启，可在应用内查看同步进度；可在系统设置中重新开启")
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            !prefs.getBoolean("notification-requested", false)) {
            prefs.edit { putBoolean("notification-requested", true) }
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
