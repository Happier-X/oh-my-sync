package dev.ohmysync.sync

import dev.ohmysync.data.LibraryArtifact
import dev.ohmysync.data.LibraryOperation

/** 同目录改名不承诺跨两次操作原子化；日志与实际内容共同决定恢复步骤。 */
interface LibraryFiles {
    suspend fun hash(name: String): String?
    suspend fun write(name: String, hash: String)
    suspend fun rename(from: String, to: String)
}

class LocalTransaction(
    private val files: LibraryFiles,
    private val save: suspend (LibraryOperation) -> Unit,
    private val complete: suspend (LibraryOperation, List<LibraryArtifact>) -> Unit,
) {
    suspend fun apply(operation: LibraryOperation) {
        val name = operation.path.substringAfterLast('/')
        val parent = operation.path.substringBeforeLast('/', "")
        fun artifact(name: String, hash: String?, kind: String) = LibraryArtifact(operation.taskId,
            operation.treeUri, if (parent.isEmpty()) name else "$parent/$name", hash, kind)
        var op = operation
        val target = files.hash(name)
        val backup = files.hash(op.backupName)
        check(backup == null || backup == op.oldHash) { "保留副本已被外部修改，未继续替换：${op.path}" }
        if (op.kind == "localTrash") {
            check(target == null || target == op.oldHash) { "删歌时手机文件已修改，保留冲突：${op.path}" }
            if (target != null) {
                check(backup == null) { "原文件与隔离副本同时存在，需检查：${op.path}" }
                files.rename(name, op.backupName)
            }
            val saved = files.hash(op.backupName)
            check(saved == op.oldHash) { "隔离文件未通过校验，保留操作记录：${op.path}" }
            complete(op, listOf(artifact(op.backupName, saved, "删除隔离")))
            return
        }
        check(op.newHash != null) { "恢复日志缺少目标内容" }
        if (target == op.newHash) {
            val retained = mutableListOf<LibraryArtifact>()
            if (backup != null) retained += artifact(op.backupName, backup, "替换旧版")
            files.hash(op.temporaryName)?.let { retained += artifact(op.temporaryName, it, "未使用临时副本") }
            complete(op, retained)
            return
        }
        check(target == op.oldHash || (target == null && (op.oldHash == null || backup == op.oldHash))) {
            "中断后目标出现其他修改，保留全部副本：${op.path}"
        }
        if (files.hash(op.temporaryName) != op.newHash) {
            check(!op.ready) { "已校验临时文件被修改，未覆盖曲库：${op.path}" }
            files.write(op.temporaryName, op.newHash)
        }
        check(files.hash(op.temporaryName) == op.newHash) { "临时文件校验失败，原文件未替换：${op.path}" }
        if (!op.ready) {
            op = op.copy(ready = true)
            save(op)
        }
        if (target != null) {
            check(backup == null && files.hash(name) == op.oldHash) { "替换前手机文件已变化：${op.path}" }
            files.rename(name, op.backupName)
            check(files.hash(op.backupName) == op.oldHash) { "旧版改名后校验失败：${op.path}" }
        }
        check(files.hash(name) == null) { "目标名称被其他文件占用，保留副本：${op.path}" }
        files.rename(op.temporaryName, name)
        check(files.hash(name) == op.newHash) { "改名后内容校验失败，未推进基线：${op.path}" }
        complete(op, if (op.oldHash == null) emptyList() else listOf(artifact(op.backupName, op.oldHash, "替换旧版")))
    }
}
