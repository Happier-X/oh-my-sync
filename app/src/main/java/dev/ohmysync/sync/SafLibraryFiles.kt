package dev.ohmysync.sync

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.ensureActive
import androidx.core.net.toUri

fun sameDocument(left: String, right: String): Boolean = left.isNotEmpty() && runCatching {
    val a = left.toUri()
    val b = right.toUri()
    a.authority == b.authority && DocumentsContract.getDocumentId(a) == DocumentsContract.getDocumentId(b)
}.getOrDefault(false)

/** 不使用 listFiles 静默吞掉枚举异常的行为，查询失败必须终止删除判断。 */
fun listLibraryFiles(context: Context, folder: DocumentFile): List<DocumentFile> {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder.uri, DocumentsContract.getDocumentId(folder.uri))
    val result = mutableListOf<DocumentFile>()
    val cursor = context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        ?: error("无法完整枚举手机目录：${folder.name}")
    cursor.use {
        while (it.moveToNext()) {
            val child = DocumentsContract.buildDocumentUriUsingTree(folder.uri, it.getString(0))
            result += DocumentFile.fromSingleUri(context, child) ?: error("无法读取手机文件身份")
        }
    }
    return result
}

class SafLibraryFiles(private val context: Context, private val folder: DocumentFile) : LibraryFiles {
    private fun find(name: String): DocumentFile? {
        val matches = listLibraryFiles(context, folder).filter { it.name == name }
        check(matches.size <= 1) { "手机目录出现重复文件名：$name" }
        return matches.singleOrNull()
    }
    override suspend fun hash(name: String): String? {
        val file = find(name) ?: return null
        check(file.isFile && file.canRead()) { "目标不可读或是文件夹：$name" }
        val before = file.lastModified()
        val size = file.length()
        val hash = context.contentResolver.openInputStream(file.uri)?.use { LibrarySync.digest(it) }
            ?: error("无法读取：$name")
        check(file.length() == size && file.lastModified() == before) { "读取期间播放器修改了文件：$name" }
        return hash
    }
    override suspend fun write(name: String, hash: String) {
        val source = File(context.filesDir, "library-history/$hash.bin")
        check(source.exists() && source.inputStream().use { LibrarySync.digest(it) } == hash) { "恢复内容缺失或损坏，未修改原文件" }
        val target = find(name) ?: DocumentsContract.createDocument(context.contentResolver, folder.uri, "application/octet-stream", name)
            ?.let { DocumentFile.fromSingleUri(context, it) } ?: error("无法创建临时文件")
        check(target.name == name && target.isFile) { "文件提供方改变了临时文件名，未替换原文件" }
        context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
            check(output != null) { "无法写入临时文件" }
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
    override suspend fun rename(from: String, to: String) {
        check(find(to) == null) { "目标名称已被占用：$to" }
        val file = find(from) ?: error("待改名文件不存在：$from")
        check(DocumentsContract.renameDocument(context.contentResolver, file.uri, to) != null) {
            "文件提供方不支持安全改名，原版和历史仍保留：$from"
        }
        check(find(to) != null && find(from) == null) { "无法确认改名结果，保留操作记录" }
    }
}
