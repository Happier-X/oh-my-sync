package dev.ohmysync

import dev.ohmysync.data.FileStamp
import dev.ohmysync.data.Paths
import dev.ohmysync.sync.SyncDecision
import dev.ohmysync.sync.decideSync
import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    @Test fun `陌生云端文件默认不会覆盖`() {
        assertEquals(SyncDecision.Conflict, decideSync("new", null, "cloud", false))
    }
    @Test fun `本任务上传的文件在云端修改后保留`() {
        assertEquals(SyncDecision.Conflict, decideSync("changed", FileStamp("old", 1, "v1"), "v2", false))
    }
    @Test fun `只更新拥有的未冲突文件`() {
        assertEquals(SyncDecision.Upload, decideSync("changed", FileStamp("old", 1, "v1"), "v1", false))
    }
    @Test fun `明确开启覆盖后允许更新`() {
        assertEquals(SyncDecision.Upload, decideSync("new", null, "cloud", true))
    }
    @Test fun `内容不变跳过而内容改变上传`() {
        val stamp = FileStamp("same", 5, "etag")
        assertEquals(SyncDecision.Unchanged, decideSync("same", stamp, "etag", false))
        assertEquals(SyncDecision.Upload, decideSync("changed", stamp, null, false))
    }
    @Test fun `云端缺失时即使本地未变化仍重新上传`() {
        assertEquals(SyncDecision.Upload, decideSync("same", FileStamp("same", 5, "etag"), null, false))
    }
    @Test fun `本地未变化而云端已变化仍报告冲突`() {
        assertEquals(SyncDecision.Conflict, decideSync("same", FileStamp("same", 5, "v1"), "v2", false))
    }
    @Test fun `缺失云端版本标记不能自动覆盖`() {
        assertEquals(SyncDecision.Conflict, decideSync("new", FileStamp("old", 1, ""), "", false))
    }
    @Test fun `目标目录拒绝穿越和不合法字符`() {
        listOf("../秘密", "备份/..", "照片/坏:名字", "备份/.", "备份/尾点.", "").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Paths.segments(value) }
        }
        assertEquals(listOf("手机备份", "照片"), Paths.segments("/手机备份/照片/"))
    }
}
