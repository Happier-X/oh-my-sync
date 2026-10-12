package dev.ohmysync

import dev.ohmysync.sync.LibraryAction
import dev.ohmysync.sync.LibraryBaseline
import dev.ohmysync.sync.decideLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDecisionTest {
    @Test fun `首次同名不同内容不会覆盖`() {
        assertEquals(LibraryAction.Conflict, decideLibrary("手机", "电脑", null))
    }
    @Test fun `首次只有一端有文件时向另一端复制`() {
        assertEquals(LibraryAction.Upload, decideLibrary("新增", null, null))
        assertEquals(LibraryAction.Download, decideLibrary(null, "新增", null))
    }
    @Test fun `只有一端修改时传播修改`() {
        val base = LibraryBaseline("共同")
        assertEquals(LibraryAction.Upload, decideLibrary("手机改", "共同", base))
        assertEquals(LibraryAction.Download, decideLibrary("共同", "电脑改", base))
    }
    @Test fun `相同内容建立或保留基线`() {
        assertEquals(LibraryAction.EstablishBaseline, decideLibrary("相同", "相同", null))
        assertEquals(LibraryAction.Unchanged, decideLibrary("相同", "相同", LibraryBaseline("相同")))
        assertEquals(LibraryAction.EstablishBaseline, decideLibrary("新", "新", LibraryBaseline("旧")))
    }
    @Test fun `单端删除只在另一端未修改时传播`() {
        val base = LibraryBaseline("共同")
        assertEquals(LibraryAction.DeleteRemote, decideLibrary(null, "共同", base))
        assertEquals(LibraryAction.DeleteLocal, decideLibrary("共同", null, base))
    }
    @Test fun `删除与修改冲突时保护修改`() {
        val base = LibraryBaseline("共同")
        assertEquals(LibraryAction.Conflict, decideLibrary(null, "电脑改", base))
        assertEquals(LibraryAction.Conflict, decideLibrary("手机改", null, base))
    }
    @Test fun `两端不同修改不会按时间猜测赢家`() {
        assertEquals(LibraryAction.Conflict, decideLibrary("手机改", "电脑改", LibraryBaseline("共同")))
    }
    @Test fun `双方删除保留墓碑并可识别后来新增`() {
        assertEquals(LibraryAction.EstablishBaseline, decideLibrary(null, null, LibraryBaseline("旧")))
        val tombstone = LibraryBaseline(null)
        assertEquals(LibraryAction.Unchanged, decideLibrary(null, null, tombstone))
        assertEquals(LibraryAction.Upload, decideLibrary("重新添加", null, tombstone))
        assertEquals(LibraryAction.Download, decideLibrary(null, "重新添加", tombstone))
    }
}
