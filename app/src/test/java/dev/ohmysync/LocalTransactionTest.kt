package dev.ohmysync

import dev.ohmysync.data.LibraryArtifact
import dev.ohmysync.data.LibraryOperation
import dev.ohmysync.sync.LibraryFiles
import dev.ohmysync.sync.LocalTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalTransactionTest {
    private fun operation(kind: String = "download", old: String? = "旧版", new: String? = "新版") = LibraryOperation(
        taskId = "task", driveId = "drive", treeUri = "tree", remotePath = "曲库", path = "专辑/歌曲.flac",
        kind = kind, oldHash = old, newHash = new, temporaryName = ".temp", backupName = ".backup")

    private class Files : LibraryFiles {
        val values = linkedMapOf<String, String>()
        var crashAfterRename = 0
        var renames = 0
        var partialWrite = false
        override suspend fun hash(name: String) = values[name]
        override suspend fun write(name: String, hash: String) {
            values[name] = if (partialWrite) "半个文件" else hash
            if (partialWrite) throw IllegalStateException("模拟写入中断")
        }
        override suspend fun rename(from: String, to: String) {
            check(!values.containsKey(to))
            values[to] = values.remove(from) ?: error("源不存在")
            renames++
            if (renames == crashAfterRename) throw IllegalStateException("模拟改名后进程终止")
        }
    }
    private class Journal(private val files: Files, var pending: LibraryOperation?) {
        var completions = 0
        var artifacts: List<LibraryArtifact> = emptyList()
        var crashBeforeCommit = false
        fun engine() = LocalTransaction(files, { pending = it }, { _, retained ->
            if (crashBeforeCommit) throw IllegalStateException("模拟提交前进程终止")
            artifacts = retained
            completions++
            pending = null
        })
        suspend fun run() { engine().apply(pending!!) }
    }

    @Test fun `正常替换保留原文件并完成日志`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版" }
        val journal = Journal(files, operation())
        journal.run()
        assertEquals("新版", files.values["歌曲.flac"])
        assertEquals("旧版", files.values[".backup"])
        assertNull(journal.pending)
        assertEquals("专辑/.backup", journal.artifacts.single().path)
    }
    @Test fun `写入一半中断原文件完整恢复会重写临时文件`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; partialWrite = true }
        val journal = Journal(files, operation())
        failure { journal.run() }
        assertEquals("旧版", files.values["歌曲.flac"])
        assertFalse(journal.pending!!.ready)
        files.partialWrite = false
        journal.run()
        assertEquals("新版", files.values["歌曲.flac"])
        assertEquals("旧版", files.values[".backup"])
    }
    @Test fun `旧文件改名后中断可以继续提交`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; crashAfterRename = 1 }
        val journal = Journal(files, operation())
        failure { journal.run() }
        assertNull(files.values["歌曲.flac"])
        assertEquals("旧版", files.values[".backup"])
        assertTrue(journal.pending!!.ready)
        journal.run()
        assertEquals("新版", files.values["歌曲.flac"])
        assertEquals(1, journal.completions)
    }
    @Test fun `新文件改名后中断恢复不重复覆盖`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; crashAfterRename = 2 }
        val journal = Journal(files, operation())
        failure { journal.run() }
        assertEquals("新版", files.values["歌曲.flac"])
        journal.run()
        assertEquals(2, files.renames)
        assertEquals(1, journal.completions)
    }
    @Test fun `提交基线前中断恢复只完成日志`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版" }
        val journal = Journal(files, operation()).apply { crashBeforeCommit = true }
        failure { journal.run() }
        journal.crashBeforeCommit = false
        journal.run()
        assertEquals(2, files.renames)
        assertNull(journal.pending)
    }
    @Test fun `中断后播放器修改目标不能覆盖`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; crashAfterRename = 1 }
        val journal = Journal(files, operation())
        failure { journal.run() }
        files.values["歌曲.flac"] = "播放器新改动"
        failure { journal.run() }
        assertEquals("播放器新改动", files.values["歌曲.flac"])
        assertEquals("旧版", files.values[".backup"])
        assertEquals("新版", files.values[".temp"])
        assertNotNull(journal.pending)
    }
    @Test fun `已校验临时文件被改不能覆盖原文件`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; values[".temp"] = "损坏" }
        val journal = Journal(files, operation().copy(ready = true))
        failure { journal.run() }
        assertEquals("旧版", files.values["歌曲.flac"])
        assertEquals(0, files.renames)
    }
    @Test fun `目标缺失但旧版也缺失不能猜测恢复`() = runBlocking {
        val journal = Journal(Files(), operation())
        failure { journal.run() }
        assertNotNull(journal.pending)
    }
    @Test fun `备份名称被占用不能替换`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; values[".backup"] = "其他文件" }
        failure { Journal(files, operation()).run() }
        assertEquals("旧版", files.values["歌曲.flac"])
        assertEquals("其他文件", files.values[".backup"])
    }
    @Test fun `首次下载可恢复中断且不产生假旧版`() = runBlocking {
        val files = Files().apply { crashAfterRename = 1 }
        val journal = Journal(files, operation(old = null))
        failure { journal.run() }
        journal.run()
        assertEquals("新版", files.values["歌曲.flac"])
        assertTrue(journal.artifacts.isEmpty())
    }
    @Test fun `删除隔离改名后中断恢复保留文件`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "旧版"; crashAfterRename = 1 }
        val journal = Journal(files, operation(kind = "localTrash", new = null))
        failure { journal.run() }
        journal.run()
        assertNull(files.values["歌曲.flac"])
        assertEquals("旧版", files.values[".backup"])
        assertEquals("删除隔离", journal.artifacts.single().kind)
    }
    @Test fun `删除与播放器修改冲突不能隔离歌曲`() = runBlocking {
        val files = Files().apply { values["歌曲.flac"] = "已刮削新版" }
        failure { Journal(files, operation(kind = "localTrash", new = null)).run() }
        assertEquals("已刮削新版", files.values["歌曲.flac"])
        assertEquals(0, files.renames)
    }
    private suspend fun failure(block: suspend () -> Unit) {
        try { block(); fail("必须保留操作并停止") } catch (_: IllegalStateException) { }
    }
}
