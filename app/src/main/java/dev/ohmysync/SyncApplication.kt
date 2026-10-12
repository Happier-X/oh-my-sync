package dev.ohmysync

import android.app.Application
import dev.ohmysync.auth.OneDriveAuth
import dev.ohmysync.cloud.OneDriveClient
import dev.ohmysync.cloud.CloudStorage
import dev.ohmysync.data.SyncStore
import dev.ohmysync.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class RunningSync(val taskId: String, val file: String, val done: Long = 0, val total: Long = 0)

class SyncApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val syncLock = Mutex()
    val running = MutableStateFlow<RunningSync?>(null)
    val http by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES).build()
    }
    val store by lazy { SyncStore(this) }
    val auth by lazy { OneDriveAuth(this, http) }
    val cloud: CloudStorage by lazy { OneDriveClient(http, auth) }
    val scheduler by lazy { SyncScheduler(this) }
}
