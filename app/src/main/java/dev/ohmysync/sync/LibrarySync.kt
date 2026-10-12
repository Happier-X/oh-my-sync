package dev.ohmysync.sync

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dev.ohmysync.SyncApplication
import dev.ohmysync.cloud.CloudItem
import dev.ohmysync.cloud.CloudStorage
import dev.ohmysync.data.LibraryStamp
import dev.ohmysync.data.LibraryVersion
import dev.ohmysync.data.Paths
import dev.ohmysync.data.SyncTask
import dev.ohmysync.data.LibraryOperation
import dev.ohmysync.data.DeleteCandidate
import dev.ohmysync.data.SyncStore
import android.content.Context
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class LibraryResult(val uploaded: Int, val downloaded: Int, val skipped: Int, val conflicts: List<String>, val pendingDeletes: List<String>, val deleted: Int = 0, val recovered: Int = 0)

/** Windows 使用普通 OneDrive 客户端，安卓负责共同基线、历史与持久操作日志。 */
class LibrarySync(private val app: SyncApplication, private val cloud: CloudStorage = app.cloud, private val store: SyncStore = app.store, private val progress: (String) -> Unit) {
    private val history = File(app.filesDir, "library-history").apply { mkdirs() }
    private data class Local(val file: DocumentFile, val hash: String, val size: Long)
    private data class Remote(val item: CloudItem, val parent: String, val hash: String, val content: File?)

    suspend fun run(task: SyncTask): LibraryResult {
        val parts = Paths.segments(task.remotePath)
        check(!parts.first().equals("轻同步历史", true)) { "曲库目录不能使用历史备份目录" }
        val root = DocumentFile.fromTreeUri(app, task.treeUri.toUri())
        check(root != null && root.isDirectory && root.canRead() && root.canWrite()) { "曲库需要读写授权，请编辑任务重新选择手机文件夹" }
        val recovered = recover(task, root)
        val state = store.current()
        val keyPrefix = "${task.driveId}/${task.id}/${task.remotePath}/"
        if (state.library.any { it.key.startsWith(keyPrefix) && it.value.hash != null }) {
            // 旧曲库根目录缺失或无权访问时不能重新创建并将其视作批量删歌。
            cloud.inspectFolder(parts)
        }
        val excluded = state.artifacts.filter { it.treeUri == task.treeUri }.associateBy { it.path }
        val local = linkedMapOf<String, Local>()
        suspend fun scan(folder: DocumentFile, prefix: String) {
            check(folder.canRead()) { "无法读取手机目录：$prefix" }
            for (file in listLibraryFiles(app, folder)) {
                currentCoroutineContext().ensureActive()
                val name = file.name ?: error("无法读取手机文件名")
                Paths.validateName(name)
                val path = prefix + name
                val artifact = excluded[path] ?: state.artifacts.find { sameDocument(it.fileUri, file.uri.toString()) }
                if (artifact != null) {
                    check(file.isFile && (artifact.hash == null || open(file).use { digest(it) } == artifact.hash)) {
                        "隔离副本已被外部修改，未继续同步：$path"
                    }
                    continue
                }
                if (file.isDirectory) scan(file, "$path/")
                else if (file.isFile) {
                    progress("检查手机 · $path")
                    val before = file.lastModified()
                    val size = file.length()
                    val hash = open(file).use { digest(it) }
                    check(file.lastModified() == before && file.length() == size) { "播放器正在修改文件，请稍后同步：$path" }
                    check(local.put(path, Local(file, hash, size)) == null) { "手机目录出现重复路径：$path" }
                }
            }
        }
        scan(root, "")
        val remoteRoot = cloud.ensureFolder(parts)
        val remote = linkedMapOf<String, Remote>()
        suspend fun scanCloud(parent: String, prefix: String) {
            for (entry in cloud.listFiles(parent)) {
                currentCoroutineContext().ensureActive()
                Paths.validateName(entry.name)
                val path = prefix + entry.name
                if (entry.item.isFolder) scanCloud(entry.item.id, "$path/")
                else {
                    check(entry.item.etag.isNotBlank()) { "云端版本标记缺失：$path" }
                    val base = state.library[keyPrefix + path]
                    var content: File? = null
                    val hash = if (base?.hash != null && base.etag == entry.item.etag) base.hash else {
                        progress("读取云端版本 · $path")
                        content = download(entry.item)
                        check(content.length() == entry.size) { "云端下载长度不一致：$path" }
                        val fresh = cloud.child(parent, entry.name)
                        check(fresh?.etag == entry.item.etag) { "电脑正在修改云端文件，请稍后同步：$path" }
                        content.inputStream().use { digest(it) }
                    }
                    remote[path] = Remote(entry.item, parent, hash, content)
                }
            }
        }
        scanCloud(remoteRoot, "")
        val archiveRoot = cloud.ensureFolder(listOf("轻同步历史", task.id))
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var deleted = 0
        val conflicts = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val knownPaths = state.library.keys.filter { it.startsWith(keyPrefix) }.map { it.removePrefix(keyPrefix) }
        val paths = (local.keys + remote.keys + knownPaths).toSortedSet()
        val actions = paths.associateWith { path -> decideLibrary(local[path]?.hash, remote[path]?.hash,
            state.library[keyPrefix + path]?.let { LibraryBaseline(it.hash) }) }
        val deleteActions = actions.filterValues { it == LibraryAction.DeleteLocal || it == LibraryAction.DeleteRemote }
        val now = System.currentTimeMillis()
        val candidates = deleteActions.map { (path, action) ->
            val hash = local[path]?.hash ?: remote.getValue(path).hash
            state.deleteCandidates.find { it.key == keyPrefix + path && it.direction == action.name && it.hash == hash }
                ?: DeleteCandidate(keyPrefix + path, action.name, hash, now)
        }
        // 只有两端完整扫描成功后才记下缺失；至少两次扫描且间隔一分钟才传播。
        store.deleteCandidates(keyPrefix, candidates)
        val massDeletion = deleteActions.size >= 10 && deleteActions.size * 4 > knownPaths.size
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            val left = local[path]
            val right = remote[path]
            val action = actions.getValue(path)
            if (action == LibraryAction.Unchanged) { skipped++; continue }
            progress("保存历史 · $path")
            if (left != null) {
                val content = capture(left)
                archive(task, path, left.hash, content, "手机", archiveRoot)
            }
            if (right != null && right.hash != left?.hash) {
                val content = right.content ?: download(right.item)
                check(content.inputStream().use { digest(it) } == right.hash) { "云端内容在同步期间发生变化：$path" }
                archive(task, path, right.hash, content, "云端", archiveRoot)
            }
            when (action) {
                LibraryAction.Upload -> {
                    check(left != null)
                    check(open(left.file).use { digest(it) } == left.hash) { "手机文件在同步期间发生变化：$path" }
                    val parent = if (path.contains('/')) cloud.ensureFolder(parts + path.substringBeforeLast('/').split('/')) else remoteRoot
                    val existing = cloud.child(parent, path.substringAfterLast('/'))
                    check(existing?.etag == right?.item?.etag) { "云端文件在同步期间发生变化：$path" }
                    progress("上传曲库 · $path")
                    val op = operation(task, path, "upload", left.hash, left.hash, existing?.etag.orEmpty(), existing?.id.orEmpty(), parent)
                    store.operation(op)
                    applyUpload(op)
                    uploaded++
                }
                LibraryAction.Download -> {
                    check(right != null)
                    val content = right.content ?: download(right.item)
                    check(content.inputStream().use { digest(it) } == right.hash) { "云端下载校验失败：$path" }
                    if (left != null) check(open(left.file).use { digest(it) } == left.hash) { "手机文件在同步期间发生变化：$path" }
                    check(cloud.child(right.parent, path.substringAfterLast('/'))?.etag == right.item.etag) { "云端文件在同步期间发生变化：$path" }
                    val parent = localFolder(app, root, path.substringBeforeLast('/', ""))
                    val target = listLibraryFiles(app, parent).singleOrNull { it.name == path.substringAfterLast('/') }
                    check(target?.uri == left?.file?.uri) { "手机目标文件在同步期间发生变化：$path" }
                    progress("下载曲库 · $path")
                    retain(content, right.hash)
                    val op = operation(task, path, "download", left?.hash, right.hash, right.item.etag, right.item.id, right.parent)
                    store.operation(op)
                    applyLocal(op, root)
                    downloaded++
                }
                LibraryAction.EstablishBaseline -> store.libraryStamp(keyPrefix + path, LibraryStamp(left?.hash, right?.item?.etag.orEmpty()))
                LibraryAction.Conflict -> conflicts += path
                LibraryAction.DeleteLocal, LibraryAction.DeleteRemote -> {
                    val candidate = candidates.first { it.key == keyPrefix + path }
                    if (!task.syncDeletes || massDeletion || now - candidate.firstSeen < 60_000) {
                        pending += path + when {
                            !task.syncDeletes -> "（未开启删除传播）"
                            massDeletion -> "（大批缺失，需人工检查）"
                            else -> "（等待下一次完整扫描确认）"
                        }
                    } else if (action == LibraryAction.DeleteLocal) {
                        check(left != null)
                        val parent = if (path.contains('/')) cloud.ensureFolder(parts + path.substringBeforeLast('/').split('/')) else remoteRoot
                        check(cloud.child(parent, path.substringAfterLast('/')) == null) { "云端歌曲已重新出现，未传播删除：$path" }
                        val op = operation(task, path, "localTrash", left.hash, null, remoteParent = parent)
                        store.operation(op)
                        applyLocal(op, root)
                        deleted++
                    } else {
                        check(right != null)
                        val parent = localFolder(app, root, path.substringBeforeLast('/', ""))
                        check(SafLibraryFiles(app, parent).hash(path.substringAfterLast('/')) == null) { "手机歌曲已重新出现，未传播删除：$path" }
                        val op = operation(task, path, "remoteTrash", right.hash, null, right.item.etag, right.item.id, right.parent, archiveRoot)
                        store.operation(op)
                        applyRemoteTrash(op, root)
                        deleted++
                    }
                }
                LibraryAction.Unchanged -> Unit
            }
        }
        return LibraryResult(uploaded, downloaded, skipped, conflicts, pending, deleted, recovered)
    }

    private fun operation(task: SyncTask, path: String, kind: String, oldHash: String?, newHash: String?,
                          etag: String = "", remoteId: String = "", remoteParent: String = "", archiveParent: String = "") =
        LibraryOperation(taskId = task.id, driveId = task.driveId, treeUri = task.treeUri, remotePath = task.remotePath,
            path = path, kind = kind, oldHash = oldHash, newHash = newHash, etag = etag,
            remoteId = remoteId, remoteParent = remoteParent, archiveParent = archiveParent)

    suspend fun recover(task: SyncTask, root: DocumentFile): Int {
        val operations = store.current().operations.filter { it.taskId == task.id }
        for (op in operations) {
            check(op.driveId == task.driveId && op.treeUri == task.treeUri && op.remotePath == task.remotePath) {
                "此任务仍有旧目录的未完成操作，请先恢复原目录配置"
            }
            progress("恢复中断操作 · ${op.path}")
            when (op.kind) {
                "download", "restore", "localTrash" -> applyLocal(op, root)
                "upload" -> applyUpload(op)
                "remoteTrash" -> applyRemoteTrash(op, root)
                else -> error("未知的恢复操作，已停止以保护曲库")
            }
        }
        return operations.size
    }

    private suspend fun applyLocal(op: LibraryOperation, root: DocumentFile) {
        val folder = localFolder(app, root, op.path.substringBeforeLast('/', ""))
        val files = SafLibraryFiles(app, folder)
        if (op.kind == "localTrash" && files.hash(op.backupName) == null) {
            check(cloud.child(op.remoteParent, op.path.substringAfterLast('/')) == null) {
                "云端歌曲已重新出现，手机原版保留：${op.path}"
            }
        }
        LocalTransaction(files, store::operation) { completed, artifacts ->
            val stamp = when (completed.kind) {
                "restore" -> null
                "localTrash" -> LibraryStamp(null)
                else -> LibraryStamp(completed.newHash, completed.etag)
            }
            val actual = listLibraryFiles(app, folder)
            val identified = artifacts.map { artifact -> artifact.copy(fileUri = actual.single {
                it.name == artifact.path.substringAfterLast('/')
            }.uri.toString()) }
            store.finishOperation(completed, stamp, identified, when (completed.kind) {
                "restore" -> "历史已恢复为副本"
                "localTrash" -> "云端删除已传播到手机，原文件已改名隔离，历史保留"
                else -> "下载已校验并提交，旧版保留"
            })
        }.apply(op)
    }

    private suspend fun applyUpload(op: LibraryOperation) {
        val name = op.path.substringAfterLast('/')
        val existing = cloud.child(op.remoteParent, name)
        if (existing != null && (existing.id != op.remoteId || existing.etag != op.etag)) {
            val content = download(existing)
            check(content.inputStream().use { digest(it) } == op.newHash) {
                "中断后云端出现其他修改，未重试覆盖：${op.path}"
            }
            store.finishOperation(op, LibraryStamp(op.newHash, existing.etag), message = "已核对中断前完成的上传")
            return
        }
        check(existing?.id.orEmpty() == op.remoteId && existing?.etag.orEmpty() == op.etag) {
            "中断后云端原文件缺失，保留操作记录：${op.path}"
        }
        val content = File(history, "${op.newHash}.bin")
        check(content.exists() && content.inputStream().use { digest(it) } == op.newHash) { "上传恢复内容损坏，未修改云端" }
        val item = cloud.upload(op.remoteParent, name, content.length(), existing, { content.inputStream() }, {})
        check(item.etag.isNotBlank()) { "上传结果缺少版本标记，下次将核对上传内容" }
        store.finishOperation(op, LibraryStamp(op.newHash, item.etag), message = "上传已提交")
    }

    private suspend fun applyRemoteTrash(op: LibraryOperation, root: DocumentFile) {
        val destinationName = "deleted-${op.id}.bin"
        val moved = cloud.child(op.archiveParent, destinationName)
        if (moved != null) {
            check(moved.id == op.remoteId && !moved.isFolder) { "云端隔离目标身份不符，未推进删除：${op.path}" }
            check(download(moved).inputStream().use { digest(it) } == op.oldHash) { "隔离文件内容变化，未推进删除" }
            store.finishOperation(op, LibraryStamp(null), message = "已核对中断前完成的云端删除隔离")
            return
        }
        val parent = localFolder(app, root, op.path.substringBeforeLast('/', ""))
        check(SafLibraryFiles(app, parent).hash(op.path.substringAfterLast('/')) == null) {
            "手机歌曲已重新出现，未传播删除：${op.path}"
        }
        val source = cloud.child(op.remoteParent, op.path.substringAfterLast('/'))
        check(source?.id == op.remoteId && source.etag == op.etag) { "云端歌曲已修改或缺失，未传播删除：${op.path}" }
        cloud.move(source, op.archiveParent, destinationName)
        val confirmed = cloud.child(op.archiveParent, destinationName)
        check(confirmed?.id == op.remoteId) { "云端移动结果尚未确认，下次同步会重新核对" }
        check(download(confirmed).inputStream().use { digest(it) } == op.oldHash) { "移动后内容校验失败，保留恢复记录" }
        store.finishOperation(op, LibraryStamp(null), message = "手机删除已传播到云端，原文件移入历史目录")
    }

    suspend fun restore(task: SyncTask, path: String, hash: String, content: File, root: DocumentFile) {
        retain(content, hash)
        val op = operation(task, path, "restore", null, hash)
        store.operation(op)
        applyLocal(op, root)
    }

    private suspend fun retain(content: File, hash: String): File {
        val saved = File(history, "$hash.bin")
        if (saved.exists() && saved.inputStream().use { digest(it) } == hash) return saved
        val temp = File(history, "${UUID.randomUUID()}.part")
        content.inputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() } }
        check(temp.inputStream().use { digest(it) } == hash) { "历史内容校验失败，未修改曲库" }
        check(temp.renameTo(saved)) { "无法持久保存恢复内容" }
        return saved
    }

    private suspend fun archive(task: SyncTask, path: String, hash: String, content: File, source: String, parent: String) {
        retain(content, hash)
        val known = store.current().versions.any { it.taskId == task.id && it.path == path && it.hash == hash }
        val name = "$hash.bin"
        val existing = cloud.child(parent, name)
        check(existing?.isFolder != true) { "历史存储中存在同名文件夹" }
        if (existing != null) {
            val saved = download(existing)
            check(saved.length() == content.length() && saved.inputStream().use { digest(it) } == hash) { "云端历史版本校验失败，未改动曲库：$path" }
        }
        val archived = existing ?: cloud.upload(parent, name, content.length(), null, { content.inputStream() }, {})
        if (known && existing != null) return
        val metadata = JSONObject().put("taskId", task.id).put("path", path).put("sha256", hash)
            .put("size", content.length()).put("source", source).put("archiveId", archived.id).put("time", System.currentTimeMillis())
            .toString().toByteArray(Charsets.UTF_8)
        cloud.upload(parent, "${UUID.randomUUID()}.json", metadata.size.toLong(), null, { metadata.inputStream() }, {})
        store.version(LibraryVersion(task.id, path, hash, content.length(), archived.id, source))
    }
    private suspend fun capture(local: Local): File {
        val result = File(history, "${local.hash}.bin")
        if (result.exists() && result.inputStream().use { digest(it) } == local.hash) return result
        val temp = File(history, "${UUID.randomUUID()}.part")
        open(local.file).use { input -> temp.outputStream().use { input.copyTo(it, 64 * 1024); it.fd.sync() } }
        check(temp.length() == local.size && temp.inputStream().use { digest(it) } == local.hash) { "历史备份时手机文件发生变化" }
        check(temp.renameTo(result)) { "无法保存手机历史版本" }
        return result
    }
    private suspend fun download(item: CloudItem): File {
        val file = File(app.cacheDir, "${UUID.randomUUID()}.download")
        file.outputStream().use { cloud.download(item, it) }
        return file
    }
    private fun open(file: DocumentFile): InputStream = app.contentResolver.openInputStream(file.uri) ?: error("无法读取：${file.name}")
    companion object {
        suspend fun digest(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun localFolder(context: Context, root: DocumentFile, relative: String): DocumentFile {
            var current = root
            if (relative.isNotEmpty()) for (name in relative.split('/')) {
                Paths.validateName(name)
                current = listLibraryFiles(context, current).singleOrNull { it.name == name }?.also { check(it.isDirectory) { "手机同名项目不是文件夹：$name" } }
                    ?: DocumentsContract.createDocument(context.contentResolver, current.uri, DocumentsContract.Document.MIME_TYPE_DIR, name)
                        ?.let { DocumentFile.fromSingleUri(context, it) } ?: error("无法创建手机目录：$name")
            }
            return current
        }
    }
}
