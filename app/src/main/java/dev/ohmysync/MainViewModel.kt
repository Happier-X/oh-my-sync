package dev.ohmysync

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dev.ohmysync.data.Account
import dev.ohmysync.data.Paths
import dev.ohmysync.data.SyncState
import dev.ohmysync.data.SyncTask
import dev.ohmysync.cloud.CloudFolder
import dev.ohmysync.data.LibraryVersion
import dev.ohmysync.sync.LibrarySync
import dev.ohmysync.sync.listLibraryFiles
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SyncApplication
    val state = app.store.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncState())
    val running = app.running
    val needsReauth = app.auth.needsReauth
    val work = WorkManager.getInstance(app).getWorkInfosByTagFlow("sync")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val account = MutableStateFlow<Account?>(app.auth.account)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val cloudFolder = MutableStateFlow<CloudFolder?>(null)
    fun inspectCloud(task: SyncTask) = action {
        check(account.value?.driveId == task.driveId) { "请连接此任务绑定的 OneDrive 账户" }
        cloudFolder.value = withContext(Dispatchers.IO) { app.cloud.inspectFolder(Paths.segments(task.remotePath)) }
    }
    fun restoreVersion(task: SyncTask, version: LibraryVersion) = action {
        check(version.taskId == task.id && account.value?.driveId == task.driveId) { "请连接此历史对应的 OneDrive 账户" }
        withContext(Dispatchers.IO) {
            app.syncLock.withLock {
                val librarySync = LibrarySync(app) {}
                val parts = Paths.segments(version.path)
                val archiveRoot = app.cloud.ensureFolder(listOf("轻同步历史", task.id))
                val item = app.cloud.child(archiveRoot, "${version.hash}.bin") ?: error("云端历史文件不存在")
                check(item.id == version.archiveId && !item.isFolder) { "云端历史文件身份已变化" }
                val temp = File(app.cacheDir, "restore-${UUID.randomUUID()}")
                temp.outputStream().use { app.cloud.download(item, it) }
                check(temp.length() == version.size && temp.inputStream().use { LibrarySync.digest(it) } == version.hash) { "历史内容校验失败，未写入曲库" }
                val root = DocumentFile.fromTreeUri(app, task.treeUri.toUri())
                check(root != null && root.isDirectory && root.canWrite()) { "请重新选择手机曲库授予写入权限" }
                librarySync.recover(task, root)
                val parent = LibrarySync.localFolder(app, root, parts.dropLast(1).joinToString("/"))
                val name = parts.last()
                val suffix = "恢复-${UUID.randomUUID().toString().take(8)}"
                val copyName = if (name.contains('.')) "${name.substringBeforeLast('.').take(160)}.$suffix.${name.substringAfterLast('.')}"
                    else "${name.take(180)}.$suffix"
                check(listLibraryFiles(app, parent).none { it.name == copyName }) { "恢复副本名称冲突，请重试" }
                val copyPath = (parts.dropLast(1) + copyName).joinToString("/")
                librarySync.restore(task, copyPath, version.hash, temp, root)
                notify("历史已恢复为副本：$copyName，原文件保留")
            }
        }
    }
    fun notify(value: String) { message.value = value }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            notify(error.message ?: "操作失败，请重试")
        }
    }
    fun connect(context: Context) = action {
        app.auth.begin(context)
    }
    fun callback(uri: Uri) = action {
        busy.value = true
        try {
            app.auth.finish(uri)
            account.value = app.auth.account
            notify("OneDrive 已连接，可以创建同步任务")
        } finally { busy.value = false }
    }
    fun disconnect() = action {
        app.scheduler.cancelAllAndWait()
        withContext(Dispatchers.IO) {
            app.syncLock.withLock {
                app.auth.disconnect()
                app.store.current().tasks.forEach { app.store.saveTask(it.copy(enabled = false)) }
            }
        }
        account.value = null
        notify("已断开 OneDrive，任务已暂停")
    }
    fun save(task: SyncTask, onSaved: () -> Unit) = action {
        check(task.name.isNotBlank()) { "请填写任务名称" }
        Paths.segments(task.remotePath)
        if (task.twoWay) check(app.contentResolver.persistedUriPermissions.any { it.uri.toString() == task.treeUri && it.isReadPermission && it.isWritePermission }) {
            "双向曲库需要读写授权，请重新选择手机文件夹"
        }
        check(task.intervalMinutes >= 15) { "后台同步间隔至少为 15 分钟" }
        check(account.value?.driveId == task.driveId) { "请连接任务对应的 OneDrive 账户" }
        check(running.value?.taskId != task.id) { "请先停止同步，再编辑任务" }
        app.scheduler.cancelAndWait(task.id)
        app.syncLock.withLock {
            val pending = app.store.current().operations.filter { it.taskId == task.id }
            check(pending.all { task.twoWay && it.driveId == task.driveId && it.treeUri == task.treeUri && it.remotePath == task.remotePath }) {
                "此任务有未完成的曲库操作，请先立即同步恢复，再修改目录或模式"
            }
            app.store.saveTask(task)
        }
        app.scheduler.schedule(task)
        onSaved()
        notify("任务已保存，可点击立即同步")
    }
    fun toggle(task: SyncTask) = action {
        if (!task.enabled) check(account.value?.driveId == task.driveId) { "请先连接此任务对应的 OneDrive 账户" }
        val updated = task.copy(enabled = !task.enabled)
        app.store.saveTask(updated)
        app.scheduler.schedule(updated)
        notify(if (updated.enabled) "已启用定时同步" else "任务已暂停")
    }
    fun sync(task: SyncTask) {
        if (!task.enabled) { notify("请先启用任务"); return }
        if (account.value?.driveId != task.driveId) { notify("请连接此任务绑定的 OneDrive 账户"); return }
        app.scheduler.now(task)
        notify("已加入同步队列，将在满足网络和充电条件后开始")
    }
    fun stop(task: SyncTask) = action {
        app.scheduler.cancelAndWait(task.id)
        app.scheduler.schedule(task)
        notify("正在停止本次同步")
    }
}
