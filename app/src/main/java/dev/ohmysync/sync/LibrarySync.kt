package dev.ohmysync.sync

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dev.ohmysync.SyncApplication
import dev.ohmysync.cloud.CloudItem
import dev.ohmysync.data.LibraryStamp
import dev.ohmysync.data.LibraryVersion
import dev.ohmysync.data.Paths
import dev.ohmysync.data.SyncTask
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class LibraryResult(val uploaded: Int, val downloaded: Int, val skipped: Int, val conflicts: List<String>, val pendingDeletes: List<String>)

/** Windows 使用普通 OneDrive 客户端，安卓负责共同基线与历史。尚不执行删除。 */
class LibrarySync(private val app: SyncApplication, private val progress: (String) -> Unit) {
    private val history = File(app.filesDir, "library-history").apply { mkdirs() }
    private data class Local(val file: DocumentFile, val hash: String, val size: Long)
    private data class Remote(val item: CloudItem, val parent: String, val hash: String, val content: File?)

    suspend fun run(task: SyncTask): LibraryResult {
        val parts = Paths.segments(task.remotePath)
        check(!parts.first().equals("轻同步历史", true)) { "曲库目录不能使用历史备份目录" }
        val root = DocumentFile.fromTreeUri(app, task.treeUri.toUri())
        check(root != null && root.isDirectory && root.canRead() && root.canWrite()) { "曲库需要读写授权，请编辑任务重新选择手机文件夹" }
        val local = linkedMapOf<String, Local>()
        suspend fun scan(folder: DocumentFile, prefix: String) {
            check(folder.canRead()) { "无法读取手机目录：$prefix" }
            for (file in folder.listFiles()) {
                currentCoroutineContext().ensureActive()
                val name = file.name ?: error("无法读取手机文件名")
                Paths.validateName(name)
                val path = prefix + name
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
        val state = app.store.current()
        val keyPrefix = "${task.driveId}/${task.id}/${task.remotePath}/"
        val remoteRoot = app.cloud.ensureFolder(parts)
        val remote = linkedMapOf<String, Remote>()
        suspend fun scanCloud(parent: String, prefix: String) {
            for (entry in app.cloud.listFiles(parent)) {
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
                        val fresh = app.cloud.child(parent, entry.name)
                        check(fresh?.etag == entry.item.etag) { "电脑正在修改云端文件，请稍后同步：$path" }
                        content.inputStream().use { digest(it) }
                    }
                    remote[path] = Remote(entry.item, parent, hash, content)
                }
            }
        }
        scanCloud(remoteRoot, "")
        val archiveRoot = app.cloud.ensureFolder(listOf("轻同步历史", task.id))
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        val conflicts = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val knownPaths = state.library.keys.filter { it.startsWith(keyPrefix) }.map { it.removePrefix(keyPrefix) }
        for (path in (local.keys + remote.keys + knownPaths).toSortedSet()) {
            currentCoroutineContext().ensureActive()
            val left = local[path]
            val right = remote[path]
            val base = state.library[keyPrefix + path]
            val action = decideLibrary(left?.hash, right?.hash, base?.let { LibraryBaseline(it.hash) })
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
                    val parent = if (path.contains('/')) app.cloud.ensureFolder(parts + path.substringBeforeLast('/').split('/')) else remoteRoot
                    val existing = app.cloud.child(parent, path.substringAfterLast('/'))
                    check(existing?.etag == right?.item?.etag) { "云端文件在同步期间发生变化：$path" }
                    val content = capture(left)
                    progress("上传曲库 · $path")
                    val item = app.cloud.upload(parent, path.substringAfterLast('/'), content.length(), existing, { content.inputStream() }, {})
                    check(open(left.file).use { digest(it) } == left.hash) { "上传时手机文件发生变化，历史已保留：$path" }
                    app.store.libraryStamp(keyPrefix + path, LibraryStamp(left.hash, item.etag))
                    uploaded++
                }
                LibraryAction.Download -> {
                    check(right != null)
                    val content = right.content ?: download(right.item)
                    check(content.inputStream().use { digest(it) } == right.hash) { "云端下载校验失败：$path" }
                    if (left != null) check(open(left.file).use { digest(it) } == left.hash) { "手机文件在同步期间发生变化：$path" }
                    check(app.cloud.child(right.parent, path.substringAfterLast('/'))?.etag == right.item.etag) { "云端文件在同步期间发生变化：$path" }
                    val parent = localFolder(root, path.substringBeforeLast('/', ""))
                    val target = parent.findFile(path.substringAfterLast('/'))
                    check(target?.uri == left?.file?.uri) { "手机目标文件在同步期间发生变化：$path" }
                    progress("下载曲库 · $path")
                    val file = target ?: parent.createFile("application/octet-stream", path.substringAfterLast('/')) ?: error("无法创建手机文件：$path")
                    app.contentResolver.openOutputStream(file.uri, "wt").use { output ->
                        check(output != null) { "无法写入手机文件：$path" }
                        content.inputStream().use { it.copyTo(output, 64 * 1024) }
                    }
                    check(open(file).use { digest(it) } == right.hash) { "写入手机校验失败，可从历史恢复：$path" }
                    app.store.libraryStamp(keyPrefix + path, LibraryStamp(right.hash, right.item.etag))
                    downloaded++
                }
                LibraryAction.EstablishBaseline -> app.store.libraryStamp(keyPrefix + path, LibraryStamp(left?.hash, right?.item?.etag.orEmpty()))
                LibraryAction.Conflict -> conflicts += path
                LibraryAction.DeleteLocal, LibraryAction.DeleteRemote -> pending += path
                LibraryAction.Unchanged -> Unit
            }
        }
        return LibraryResult(uploaded, downloaded, skipped, conflicts, pending)
    }

    private suspend fun archive(task: SyncTask, path: String, hash: String, content: File, source: String, parent: String) {
        val known = app.store.current().versions.any { it.taskId == task.id && it.path == path && it.hash == hash }
        val name = "$hash.bin"
        val existing = app.cloud.child(parent, name)
        check(existing?.isFolder != true) { "历史存储中存在同名文件夹" }
        if (existing != null) {
            val saved = download(existing)
            check(saved.length() == content.length() && saved.inputStream().use { digest(it) } == hash) { "云端历史版本校验失败，未改动曲库：$path" }
        }
        val archived = existing ?: app.cloud.upload(parent, name, content.length(), null, { content.inputStream() }, {})
        if (known && existing != null) return
        val metadata = JSONObject().put("taskId", task.id).put("path", path).put("sha256", hash)
            .put("size", content.length()).put("source", source).put("archiveId", archived.id).put("time", System.currentTimeMillis())
            .toString().toByteArray(Charsets.UTF_8)
        app.cloud.upload(parent, "${UUID.randomUUID()}.json", metadata.size.toLong(), null, { metadata.inputStream() }, {})
        app.store.version(LibraryVersion(task.id, path, hash, content.length(), archived.id, source))
    }
    private suspend fun capture(local: Local): File {
        val result = File(history, "${local.hash}.bin")
        if (result.exists() && result.inputStream().use { digest(it) } == local.hash) return result
        val temp = File(history, "${UUID.randomUUID()}.part")
        open(local.file).use { input -> temp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        check(temp.length() == local.size && temp.inputStream().use { digest(it) } == local.hash) { "历史备份时手机文件发生变化" }
        check(temp.renameTo(result)) { "无法保存手机历史版本" }
        return result
    }
    private suspend fun download(item: CloudItem): File {
        val file = File(app.cacheDir, "${UUID.randomUUID()}.download")
        file.outputStream().use { app.cloud.download(item, it) }
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
        fun localFolder(root: DocumentFile, relative: String): DocumentFile {
            var current = root
            if (relative.isNotEmpty()) for (name in relative.split('/')) {
                Paths.validateName(name)
                current = current.findFile(name)?.also { check(it.isDirectory) { "手机同名项目不是文件夹：$name" } }
                    ?: current.createDirectory(name) ?: error("无法创建手机目录：$name")
            }
            return current
        }
    }
}
