package dev.ohmysync.sync

import dev.ohmysync.data.FileStamp

enum class SyncDecision { Unchanged, Upload, Conflict }

/** 单向同步只自动更新本任务拥有、且云端版本仍一致的文件。 */
fun decideSync(hash: String, previous: FileStamp?, remoteEtag: String?, overwrite: Boolean): SyncDecision = when {
    remoteEtag == null -> SyncDecision.Upload
    previous?.hash == hash && previous.etag.isNotEmpty() && previous.etag == remoteEtag -> SyncDecision.Unchanged
    overwrite -> SyncDecision.Upload
    previous != null && previous.etag.isNotEmpty() && previous.etag == remoteEtag -> SyncDecision.Upload
    else -> SyncDecision.Conflict
}
