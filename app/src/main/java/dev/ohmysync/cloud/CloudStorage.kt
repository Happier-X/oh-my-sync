package dev.ohmysync.cloud

import java.io.InputStream

/** 新的云盘提供方只需实现目录、查询和上传接口。 */
interface CloudStorage {
    suspend fun move(item: CloudItem, parent: String, name: String): CloudItem
    suspend fun listFiles(parent: String): List<RemoteEntry>
    suspend fun download(item: CloudItem, output: java.io.OutputStream)
    suspend fun inspectFolder(parts: List<String>): CloudFolder
    suspend fun ensureFolder(parts: List<String>): String
    suspend fun child(parent: String, name: String): CloudItem?
    suspend fun upload(parent: String, name: String, size: Long, existing: CloudItem?,
                       stream: () -> InputStream, onProgress: suspend (Long) -> Unit): CloudItem
}

data class CloudEntry(val name: String, val size: Long, val isFolder: Boolean)
data class RemoteEntry(val name: String, val size: Long, val item: CloudItem)
data class CloudFolder(val webUrl: String, val entries: List<CloudEntry>, val hasMore: Boolean)
