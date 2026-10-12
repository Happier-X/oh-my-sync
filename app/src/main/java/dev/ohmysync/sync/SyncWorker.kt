package dev.ohmysync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.ohmysync.MainActivity
import dev.ohmysync.R
import dev.ohmysync.RunningSync
import dev.ohmysync.SyncApplication
import dev.ohmysync.auth.AuthRequired
import dev.ohmysync.cloud.CloudError
import dev.ohmysync.data.FileStamp
import dev.ohmysync.data.Paths
import dev.ohmysync.data.SyncRecord
import dev.ohmysync.data.SyncTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val app = context.applicationContext as SyncApplication
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("taskId") ?: return@withContext Result.failure()
        app.syncLock.withLock {
            val task = app.store.current().tasks.find { it.id == id && it.enabled }
                ?: return@withLock Result.success()
            var uploaded = 0
            var skipped = 0
            var conflicts = 0
            var bytes = 0L
            var firstConflict = ""
            try {
                val account = app.auth.account ?: throw AuthRequired("请先连接 OneDrive")
                check(account.driveId == task.driveId) { "此任务绑定了其他 OneDrive 账户，请连接原账户或新建任务" }
                setForeground(notification(task.name))
                app.running.value = RunningSync(id, "正在扫描文件夹")
                val root = DocumentFile.fromTreeUri(applicationContext, task.treeUri.toUri())
                check(root != null && root.isDirectory && root.canRead()) { "本地文件夹授权已失效，请重新选择文件夹" }
                if (task.twoWay) {
                    val result = LibrarySync(app) { app.running.value = RunningSync(id, it) }.run(task)
                    val issues = result.conflicts.size + result.pendingDeletes.size
                    val details = buildString {
                        append("曲库同步：上传 ${result.uploaded} 个，下载 ${result.downloaded} 个，未变化 ${result.skipped} 个")
                        if (result.conflicts.isNotEmpty()) append("；保留 ${result.conflicts.size} 个冲突：${result.conflicts.first()}")
                        if (result.pendingDeletes.isNotEmpty()) append("；${result.pendingDeletes.size} 个删除待确认：${result.pendingDeletes.first()}")
                    }
                    app.store.record(SyncRecord(id, task.name, status = if (issues == 0) "success" else "conflict",
                        message = details, uploaded = result.uploaded, skipped = result.skipped))
                    return@withLock Result.success()
                }
                val remoteRoot = app.cloud.ensureFolder(Paths.segments(task.remotePath))
                val stamps = app.store.current().stamps.toMutableMap()
                suspend fun walk(directory: DocumentFile, parent: String, prefix: String) {
                    currentCoroutineContext().ensureActive()
                    check(directory.canRead()) { "无法读取文件夹：$prefix" }
                    for (file in directory.listFiles()) {
                        currentCoroutineContext().ensureActive()
                        val name = file.name ?: error("无法读取文件名")
                        Paths.validateName(name)
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (file.isDirectory) {
                            val child = app.cloud.ensureFolder(Paths.segments(task.remotePath) + path.split('/'))
                            walk(file, child, path)
                        } else if (file.isFile) {
                            check(file.canRead()) { "无法读取文件：$path" }
                            app.running.value = RunningSync(id, "检查 $path")
                            val initialModified = file.lastModified()
                            val digest = MessageDigest.getInstance("SHA-256")
                            var size = 0L
                            open(file).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    digest.update(buffer, 0, count)
                                    size += count
                                }
                            }
                            val hash = digest.digest().joinToString("") { "%02x".format(it) }
                            val stampKey = "${task.driveId}/${task.id}/${task.remotePath}/$path"
                            val previous = stamps[stampKey]
                            val existing = app.cloud.child(parent, name)
                            val decision = decideSync(hash, previous, existing?.etag, task.overwrite)
                            if (decision == SyncDecision.Unchanged) { skipped++; continue }
                            if (decision == SyncDecision.Conflict) {
                                conflicts++
                                if (firstConflict.isEmpty()) firstConflict = path
                                continue
                            }
                            check(file.lastModified() == initialModified && file.length() == size) { "扫描时文件已变化，请重试：$path" }
                            val item = app.cloud.upload(parent, name, size, existing, { open(file) }) { sent ->
                                app.running.value = RunningSync(id, path, sent, size)
                                setProgress(androidx.work.workDataOf("file" to path, "done" to sent, "total" to size))
                            }
                            check(file.lastModified() == initialModified && file.length() == size) { "上传时文件已变化，请重试：$path" }
                            val stamp = FileStamp(hash, size, item.etag)
                            app.store.stamp(stampKey, stamp)
                            stamps[stampKey] = stamp
                            uploaded++
                            bytes += size
                        }
                    }
                }
                walk(root, remoteRoot, "")
                val message = if (conflicts == 0) "文件夹已同步，上传 $uploaded 个，未变化 $skipped 个"
                    else "上传 $uploaded 个，未变化 $skipped 个，保留 $conflicts 个云端冲突；首个：$firstConflict"
                app.store.record(SyncRecord(id, task.name, status = if (conflicts == 0) "success" else "conflict",
                    message = message, uploaded = uploaded, skipped = skipped, bytes = bytes))
                Result.success()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    app.store.record(SyncRecord(id, task.name, status = "cancelled", message = "同步已停止", uploaded = uploaded, bytes = bytes))
                }
                throw cancelled
            } catch (error: Exception) {
                val retryable = error is IOException &&
                    (error !is CloudError || error.code in listOf(429, 500, 502, 503, 504))
                val retry = retryable && runAttemptCount < 3
                app.store.record(SyncRecord(id, task.name, status = "error", message =
                    (error.message ?: "同步失败") + if (retry) "；稍后自动重试" else "", uploaded = uploaded, bytes = bytes))
                if (retry) Result.retry() else Result.failure()
            } finally {
                app.running.value = null
            }
        }
    }
    private fun open(file: DocumentFile) = applicationContext.contentResolver.openInputStream(file.uri)
        ?: throw IOException("无法打开本地文件：${file.name}")
    private fun notification(name: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sync", "文件同步", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notification.Builder(applicationContext, "sync").setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("正在同步 · $name").setContentText("手机文件夹 → OneDrive")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", cancel).build()).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(42, notification)
    }
}
