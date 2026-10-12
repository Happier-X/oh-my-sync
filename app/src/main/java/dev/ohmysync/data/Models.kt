package dev.ohmysync.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SyncTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val treeUri: String,
    val localName: String,
    val remotePath: String,
    val driveId: String,
    val intervalMinutes: Long = 60,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val enabled: Boolean = true,
    val overwrite: Boolean = false,
    val twoWay: Boolean = false,
    val syncDeletes: Boolean = false,
)

@Serializable
data class SyncRecord(
    val taskId: String,
    val taskName: String,
    val time: Long = System.currentTimeMillis(),
    val status: String,
    val message: String,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val bytes: Long = 0,
)

@Serializable
data class FileStamp(val hash: String, val size: Long, val etag: String)

@Serializable
data class SyncState(
    val tasks: List<SyncTask> = emptyList(),
    val records: List<SyncRecord> = emptyList(),
    val stamps: Map<String, FileStamp> = emptyMap(),
    val library: Map<String, LibraryStamp> = emptyMap(),
    val versions: List<LibraryVersion> = emptyList(),
    val operations: List<LibraryOperation> = emptyList(),
    val artifacts: List<LibraryArtifact> = emptyList(),
    val libraryEvents: List<LibraryEvent> = emptyList(),
    val deleteCandidates: List<DeleteCandidate> = emptyList(),
)

@Serializable
data class LibraryStamp(val hash: String?, val etag: String = "")

/** 修改曲库前持久化，恢复时使用记录的目录与内容，不能套用编辑后的任务。 */
@Serializable
data class LibraryOperation(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String, val driveId: String, val treeUri: String, val remotePath: String,
    val path: String, val kind: String,
    val oldHash: String? = null, val newHash: String? = null, val etag: String = "",
    val remoteId: String = "", val remoteParent: String = "", val archiveParent: String = "",
    val temporaryName: String = ".ohmysync-${UUID.randomUUID()}.part",
    val backupName: String = ".ohmysync-${UUID.randomUUID()}.backup",
    val ready: Boolean = false,
) {
    val key: String get() = "$driveId/$taskId/$remotePath/$path"
}

@Serializable
data class LibraryArtifact(val taskId: String, val treeUri: String, val path: String, val hash: String?, val kind: String, val fileUri: String = "")

@Serializable
data class LibraryEvent(val taskId: String, val path: String, val message: String, val time: Long = System.currentTimeMillis())

@Serializable
data class DeleteCandidate(val key: String, val direction: String, val hash: String, val firstSeen: Long)

@Serializable
data class LibraryVersion(val taskId: String, val path: String, val hash: String,
                          val size: Long, val archiveId: String, val source: String,
                          val time: Long = System.currentTimeMillis())

@Serializable
data class Account(val driveId: String, val name: String, val remaining: Long = 0)

@Serializable
data class Tokens(val access: String, val refresh: String, val expiresAt: Long)

object Paths {
    private val forbidden = Regex("[\"*:<>?\\\\|]")
    fun segments(path: String): List<String> {
        val parts = path.trim().trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "请填写 OneDrive 目标文件夹" }
        parts.forEach { validateName(it) }
        return parts
    }
    fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') &&
            !forbidden.containsMatchIn(name) && !name.endsWith('.') && !name.endsWith(' ')) {
            "OneDrive 不支持文件名：$name"
        }
    }
}
