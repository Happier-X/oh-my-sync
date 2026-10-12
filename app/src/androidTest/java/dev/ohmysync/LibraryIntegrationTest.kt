package dev.ohmysync

import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.ohmysync.cloud.*
import dev.ohmysync.data.*
import dev.ohmysync.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class LibraryIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SyncApplication
    private class MemoryCloud : CloudStorage {
        data class Node(val id: String, var parent: String, var name: String, var bytes: ByteArray?, var etag: String, var visible: Boolean = true)
        val nodes = mutableListOf(Node("root", "", "", null, "root"))
        var sequence = 0
        var failAfterUploadName: String? = null
        var failAfterMove = false
        var failListParent: String? = null
        var moves = 0
        fun find(parent: String, name: String) = nodes.singleOrNull { it.visible && it.parent == parent && it.name == name }
        fun edit(parent: String, name: String, content: String) {
            val node = find(parent, name) ?: Node(UUID.randomUUID().toString(), parent, name, byteArrayOf(), "").also { nodes += it }
            node.bytes = content.toByteArray(); node.etag = "v${++sequence}"
        }
        fun item(node: Node) = CloudItem(node.id, node.etag, node.bytes == null)
        override suspend fun ensureFolder(parts: List<String>): String {
            var parent = "root"
            for (name in parts) {
                val node = find(parent, name) ?: Node(UUID.randomUUID().toString(), parent, name, null, "v${++sequence}").also { nodes += it }
                check(node.bytes == null); parent = node.id
            }
            return parent
        }
        override suspend fun inspectFolder(parts: List<String>): CloudFolder {
            var parent = "root"
            for (name in parts) parent = find(parent, name)?.id ?: error("云端根缺失")
            return CloudFolder("https://example.invalid", emptyList(), false)
        }
        override suspend fun child(parent: String, name: String) = find(parent, name)?.let(::item)
        override suspend fun listFiles(parent: String): List<RemoteEntry> {
            if (parent == failListParent) throw java.io.IOException("模拟枚举中断")
            return nodes.filter { it.visible && it.parent == parent }.map {
                RemoteEntry(it.name, it.bytes?.size?.toLong() ?: 0, item(it))
            }
        }
        override suspend fun download(item: CloudItem, output: OutputStream) {
            val node = nodes.single { it.visible && it.id == item.id }
            check(node.etag == item.etag); output.write(node.bytes!!)
        }
        override suspend fun upload(parent: String, name: String, size: Long, existing: CloudItem?, stream: () -> InputStream, onProgress: suspend (Long) -> Unit): CloudItem {
            val old = find(parent, name)
            check(old?.id == existing?.id && old?.etag == existing?.etag)
            val node = old ?: Node(UUID.randomUUID().toString(), parent, name, byteArrayOf(), "").also { nodes += it }
            node.bytes = stream().use { it.readBytes() }; check(node.bytes!!.size.toLong() == size)
            node.etag = "v${++sequence}"
            if (name == failAfterUploadName) { failAfterUploadName = null; throw java.io.IOException("模拟上传成功但响应丢失") }
            return item(node)
        }
        override suspend fun move(item: CloudItem, parent: String, name: String): CloudItem {
            val node = nodes.single { it.visible && it.id == item.id }
            check(node.etag == item.etag && find(parent, name) == null)
            node.parent = parent; node.name = name; node.etag = "v${++sequence}"
            moves++
            if (failAfterMove) { failAfterMove = false; throw java.io.IOException("模拟移动成功但响应丢失") }
            return item(node)
        }
    }
    private data class Fixture(val root: DocumentFile, val task: SyncTask, val store: SyncStore, val cloud: MemoryCloud)
    private fun fixture(): Fixture {
        val id = app.contentResolver.call("dev.ohmysync.test.documents", "newRoot", null, null)!!.getString("id")!!
        val uri = DocumentsContract.buildTreeDocumentUri("dev.ohmysync.test.documents", id)
        val task = SyncTask(name = "隔离测试", treeUri = uri.toString(), localName = id, remotePath = "隔离测试/$id", driveId = "fake-drive", twoWay = true, syncDeletes = true)
        return Fixture(DocumentFile.fromTreeUri(app, uri)!!, task, SyncStore(app, "library-test-$id"), MemoryCloud())
    }
    private fun write(folder: DocumentFile, name: String, content: String) {
        val file = listLibraryFiles(app, folder).singleOrNull { it.name == name } ?: DocumentsContract.createDocument(app.contentResolver, folder.uri, "application/octet-stream", name)
            ?.let { DocumentFile.fromSingleUri(app, it) } ?: error("创建失败")
        app.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(content.toByteArray()) }
    }
    private fun read(folder: DocumentFile, name: String): String? = listLibraryFiles(app, folder).singleOrNull { it.name == name }
        ?.let { app.contentResolver.openInputStream(it.uri)!!.use { input -> input.reader().readText() } }
    private fun sync(f: Fixture) = LibrarySync(app, f.cloud, f.store) {}
    private suspend fun confirmMissing(f: Fixture) {
        val prefix = "${f.task.driveId}/${f.task.id}/${f.task.remotePath}/"
        f.store.deleteCandidates(prefix, f.store.current().deleteCandidates.map { it.copy(firstSeen = System.currentTimeMillis() - 61_000) })
    }

    @Test fun 双向新增修改冲突删除隔离与墓碑() = runBlocking {
        val f = fixture()
        write(f.root, "歌曲.flac", "手机初版")
        assertEquals(1, sync(f).run(f.task).uploaded)
        val remoteRoot = f.cloud.ensureFolder(Paths.segments(f.task.remotePath))
        f.cloud.edit(remoteRoot, "歌曲.flac", "电脑刮削版")
        assertEquals(1, sync(f).run(f.task).downloaded)
        assertEquals("电脑刮削版", read(f.root, "歌曲.flac"))
        assertEquals(1, f.store.current().artifacts.size)
        assertFalse(f.cloud.nodes.any { it.parent == remoteRoot && it.name.endsWith(".backup") })
        write(f.root, "歌曲.flac", "手机再刮削")
        f.cloud.edit(remoteRoot, "歌曲.flac", "电脑再刮削")
        assertEquals(listOf("歌曲.flac"), sync(f).run(f.task).conflicts)
        assertEquals("手机再刮削", read(f.root, "歌曲.flac"))
        f.cloud.edit(remoteRoot, "歌曲.flac", "手机再刮削")
        sync(f).run(f.task)
        f.cloud.find(remoteRoot, "歌曲.flac")!!.visible = false
        val first = sync(f).run(f.task)
        assertEquals(0, first.deleted)
        assertNotNull(read(f.root, "歌曲.flac"))
        confirmMissing(f)
        assertEquals(1, sync(f).run(f.task).deleted)
        assertNull(read(f.root, "歌曲.flac"))
        assertNull(f.store.current().library.values.single().hash)
        assertTrue(f.store.current().operations.isEmpty())
        f.cloud.edit(remoteRoot, "歌曲.flac", "重新加入的歌曲")
        assertEquals(1, sync(f).run(f.task).downloaded)
        assertEquals("重新加入的歌曲", read(f.root, "歌曲.flac"))
    }
    @Test fun 手机改名会归档旧云端路径且默认关闭删除不执行() = runBlocking {
        val f = fixture()
        write(f.root, "旧名.flac", "歌曲内容")
        sync(f).run(f.task)
        val file = listLibraryFiles(app, f.root).single { it.name == "旧名.flac" }
        assertNotNull(DocumentsContract.renameDocument(app.contentResolver, file.uri, "新名.flac"))
        val result = sync(f).run(f.task.copy(syncDeletes = false))
        assertEquals(1, result.uploaded)
        assertEquals(0, result.deleted)
        confirmMissing(f)
        assertEquals(1, sync(f).run(f.task).deleted)
        val remoteRoot = f.cloud.ensureFolder(Paths.segments(f.task.remotePath))
        assertNull(f.cloud.child(remoteRoot, "旧名.flac"))
        assertNotNull(f.cloud.child(remoteRoot, "新名.flac"))
        assertTrue(f.cloud.nodes.any { it.name.startsWith("deleted-") && it.bytes!!.decodeToString() == "歌曲内容" })
    }
    @Test fun 真实文件提供方改名中断后恢复排除旧版副本() = runBlocking {
        val f = fixture()
        write(f.root, "歌曲.flac", "原版")
        sync(f).run(f.task)
        val rootId = f.cloud.ensureFolder(Paths.segments(f.task.remotePath))
        f.cloud.edit(rootId, "歌曲.flac", "云端新版")
        // 使用真实 SAF 操作在旧文件改名后模拟进程退出，日志写入真实 DataStore。
        val oldHash = "原版".byteInputStream().use { LibrarySync.digest(it) }
        val newHash = "云端新版".byteInputStream().use { LibrarySync.digest(it) }
        val durable = java.io.File(app.filesDir, "library-history/$newHash.bin")
        durable.parentFile!!.mkdirs(); durable.writeText("云端新版")
        val item = f.cloud.child(rootId, "歌曲.flac")!!
        val op = LibraryOperation(taskId = f.task.id, driveId = f.task.driveId, treeUri = f.task.treeUri, remotePath = f.task.remotePath,
            path = "歌曲.flac", kind = "download", oldHash = oldHash, newHash = newHash, etag = item.etag, remoteId = item.id, remoteParent = rootId)
        f.store.operation(op)
        val files = SafLibraryFiles(app, f.root)
        val interrupted = object : LibraryFiles by files {
            override suspend fun rename(from: String, to: String) { files.rename(from, to); error("模拟退出") }
        }
        try { LocalTransaction(interrupted, f.store::operation) { _, _ -> fail("不应提前提交") }.apply(op); fail("应中断") }
        catch (_: IllegalStateException) { }
        assertNull(read(f.root, "歌曲.flac"))
        assertEquals("原版", read(f.root, op.backupName))
        val resumed = sync(f).run(f.task)
        assertEquals(1, resumed.recovered)
        assertEquals("云端新版", read(f.root, "歌曲.flac"))
        assertNull(f.cloud.child(rootId, op.backupName))
        assertTrue(f.store.current().operations.isEmpty())
        assertEquals(1, f.store.current().artifacts.size)
    }
    @Test fun 上传成功响应丢失后核对云端并完成原日志() = runBlocking {
        val f = fixture()
        write(f.root, "歌曲.flac", "新增歌曲")
        f.cloud.failAfterUploadName = "歌曲.flac"
        try { sync(f).run(f.task); fail("应模拟网络中断") } catch (_: java.io.IOException) { }
        assertEquals("upload", f.store.current().operations.single().kind)
        val remoteRoot = f.cloud.ensureFolder(Paths.segments(f.task.remotePath))
        val committed = f.cloud.child(remoteRoot, "歌曲.flac")!!
        val result = sync(f).run(f.task)
        assertEquals(1, result.recovered)
        assertEquals(0, result.uploaded)
        assertEquals(committed, f.cloud.child(remoteRoot, "歌曲.flac"))
        assertTrue(f.store.current().operations.isEmpty())
    }
    @Test fun 云端隔离响应丢失后按身份确认不重复移动() = runBlocking {
        val f = fixture()
        write(f.root, "旧名.flac", "歌曲内容")
        sync(f).run(f.task)
        val original = listLibraryFiles(app, f.root).single { it.name == "旧名.flac" }
        DocumentsContract.renameDocument(app.contentResolver, original.uri, "新名.flac")
        sync(f).run(f.task)
        confirmMissing(f)
        f.cloud.failAfterMove = true
        try { sync(f).run(f.task); fail("应模拟响应丢失") } catch (_: java.io.IOException) { }
        assertEquals("remoteTrash", f.store.current().operations.single().kind)
        assertEquals(1, sync(f).run(f.task).recovered)
        assertEquals(1, f.cloud.moves)
        assertTrue(f.store.current().operations.isEmpty())
        assertEquals("歌曲内容", read(f.root, "新名.flac"))
    }
    @Test fun 大批缺失即使开启传播也只提示() = runBlocking {
        val f = fixture()
        for (i in 1..12) write(f.root, "歌曲$i.flac", "内容$i")
        sync(f).run(f.task)
        val remoteRoot = f.cloud.ensureFolder(Paths.segments(f.task.remotePath))
        f.cloud.nodes.filter { it.parent == remoteRoot }.forEach { it.visible = false }
        sync(f).run(f.task)
        confirmMissing(f)
        val result = sync(f).run(f.task)
        assertEquals(0, result.deleted)
        assertEquals(12, result.pendingDeletes.size)
        for (i in 1..12) assertEquals("内容$i", read(f.root, "歌曲$i.flac"))
    }
    @Test fun 枚举中断和曲库根丢失不能作为删除依据() = runBlocking {
        val f = fixture()
        write(f.root, "歌曲.flac", "保留歌曲")
        sync(f).run(f.task)
        val remoteRoot = f.cloud.ensureFolder(Paths.segments(f.task.remotePath))
        f.cloud.find(remoteRoot, "歌曲.flac")!!.visible = false
        sync(f).run(f.task)
        confirmMissing(f)
        f.cloud.failListParent = remoteRoot
        try { sync(f).run(f.task); fail("不完整扫描必须停止") } catch (_: java.io.IOException) { }
        assertEquals("保留歌曲", read(f.root, "歌曲.flac"))
        assertTrue(f.store.current().operations.isEmpty())
        f.cloud.failListParent = null
        f.cloud.nodes.single { it.id == remoteRoot }.visible = false
        try { sync(f).run(f.task); fail("根缺失必须停止") } catch (_: IllegalStateException) { }
        assertEquals("保留歌曲", read(f.root, "歌曲.flac"))
        assertTrue(f.store.current().operations.isEmpty())
    }
}
