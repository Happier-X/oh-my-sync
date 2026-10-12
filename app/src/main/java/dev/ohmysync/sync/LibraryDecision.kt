package dev.ohmysync.sync

/** null 表示文件不存在；所有比较都基于完整内容 SHA-256，而非修改时间。 */
enum class LibraryAction { Unchanged, EstablishBaseline, Upload, Download, DeleteLocal, DeleteRemote, Conflict }

data class LibraryBaseline(val hash: String?)

/** 冲突不能推进共同基线；删除是可审核的计划，调用方不得直接执行。 */
fun decideLibrary(localHash: String?, remoteHash: String?, baseline: LibraryBaseline?): LibraryAction {
    if (localHash == remoteHash) {
        return if (baseline != null && baseline.hash == localHash) LibraryAction.Unchanged
        else LibraryAction.EstablishBaseline
    }
    if (baseline == null) return when {
        remoteHash == null -> LibraryAction.Upload
        localHash == null -> LibraryAction.Download
        else -> LibraryAction.Conflict
    }
    return when {
        localHash == baseline.hash -> if (remoteHash == null) LibraryAction.DeleteLocal else LibraryAction.Download
        remoteHash == baseline.hash -> if (localHash == null) LibraryAction.DeleteRemote else LibraryAction.Upload
        else -> LibraryAction.Conflict
    }
}
