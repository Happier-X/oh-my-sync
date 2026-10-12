package dev.ohmysync.cloud

import dev.ohmysync.auth.OneDriveAuth
import dev.ohmysync.data.Paths
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CloudError(val code: Int, detail: String = "") : IOException((when (code) {
    401, 403 -> "OneDrive 权限不足，请重新授权（$code）"
    409, 412 -> "云端文件已存在或已被修改，已保留云端文件（$code）"
    507 -> "OneDrive 空间不足"
    else -> "OneDrive 请求失败（$code）"
}) + if (detail.isBlank()) "" else "；$detail")

data class CloudItem(val id: String, val etag: String, val isFolder: Boolean)

class OneDriveClient(private val http: OkHttpClient, private val token: suspend (Boolean) -> String,
                     private val base: String = "https://graph.microsoft.com/v1.0/me/drive") : CloudStorage {
    constructor(http: OkHttpClient, auth: OneDriveAuth) : this(http, { force -> auth.token(force) })
    override suspend fun listFiles(parent: String): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        var url: String? = "$base/items/${enc(parent)}/children?\$top=200"
        while (url != null) {
            val pageUrl = url.toHttpUrl()
            val origin = base.toHttpUrl()
            check(pageUrl.scheme == origin.scheme && pageUrl.host == origin.host && pageUrl.port == origin.port) { "微软返回了无效的分页地址" }
            val page = request(url)
            val values = page.getJSONArray("value")
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                check(!item.has("remoteItem")) { "曲库暂不支持云端快捷方式" }
                result += RemoteEntry(item.getString("name"), item.optLong("size"),
                    CloudItem(item.getString("id"), item.optString("eTag"), item.has("folder")))
            }
            url = page.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        return result
    }
    override suspend fun download(item: CloudItem, output: java.io.OutputStream) {
        check(item.etag.isNotBlank()) { "云端版本标记缺失，无法安全下载" }
        val metadataUrl = "$base/items/${enc(item.id)}"
        check(request(metadataUrl).optString("eTag") == item.etag) { "云端版本已变化，请重新同步" }
        for (attempt in 0..1) {
            val req = Request.Builder().url("$base/items/${enc(item.id)}/content")
                .header("Authorization", "Bearer ${token(attempt == 1)}").build()
            val response = http.newCall(req).await()
            response.use {
                if (it.code == 401 && attempt == 0) return@use
                if (!it.isSuccessful) throw cloudError(Reply(it.code, it.body.string(), 0), "下载云端版本")
                it.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                check(request(metadataUrl).optString("eTag") == item.etag) { "下载期间云端版本已变化，未应用到曲库" }
                return
            }
        }
        throw CloudError(401)
    }
    override suspend fun inspectFolder(parts: List<String>): CloudFolder {
        parts.forEach(Paths::validateName)
        val folder = request("$base/root:/${parts.joinToString("/") { enc(it) }}")
        check(folder.has("folder")) { "目标位置不是云端文件夹" }
        val children = request("$base/items/${enc(folder.getString("id"))}/children?\$top=50")
        val values = children.getJSONArray("value")
        val entries = (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            CloudEntry(item.getString("name"), item.optLong("size"), item.has("folder"))
        }
        val webUrl = folder.optString("webUrl")
        check(webUrl.startsWith("https://")) { "微软未返回有效的云端目录地址" }
        return CloudFolder(webUrl, entries, children.has("@odata.nextLink"))
    }
    override suspend fun ensureFolder(parts: List<String>): String {
        var parent = request("$base/root").getString("id")
        for (name in parts) {
            Paths.validateName(name)
            val existing = child(parent, name)
            if (existing != null) {
                check(existing.isFolder) { "云端同名项目不是文件夹：$name" }
                parent = existing.id
            } else {
                val body = JSONObject().put("name", name).put("folder", JSONObject())
                    .put("@microsoft.graph.conflictBehavior", "fail")
                parent = try {
                    request("$base/items/${enc(parent)}/children", "POST", body).getString("id")
                } catch (error: CloudError) {
                    if (error.code != 409) throw error
                    val raced = child(parent, name)
                    check(raced?.isFolder == true) { "云端目录创建冲突：$name" }
                    raced.id
                }
            }
        }
        return parent
    }
    override suspend fun child(parent: String, name: String): CloudItem? = try {
        val value = request("$base/items/${enc(parent)}:/${enc(name)}")
        CloudItem(value.getString("id"), value.optString("eTag"), value.has("folder"))
    } catch (error: CloudError) {
        if (error.code == 404) null else throw error
    }

    override suspend fun upload(
        parent: String, name: String, size: Long, existing: CloudItem?,
        stream: () -> InputStream, onProgress: suspend (Long) -> Unit,
    ): CloudItem {
        Paths.validateName(name)
        check(existing?.isFolder != true) { "目标位置已存在同名文件夹：$name" }
        if (size == 0L) {
            val url = if (existing != null) "$base/items/${enc(existing.id)}/content"
                else "$base/items/${enc(parent)}:/${enc(name)}:/content?%40microsoft.graph.conflictBehavior=fail"
            val value = request(url, "PUT", raw = byteArrayOf(), etag = existing?.etag)
            return CloudItem(value.getString("id"), value.optString("eTag"), false)
        }
        val url = if (existing != null) "$base/items/${enc(existing.id)}/createUploadSession"
            else "$base/items/${enc(parent)}:/${enc(name)}:/createUploadSession"
        // 文件名已在路径中；省略重复 name，避免 OneDrive 对属性顺序的兼容性问题。
        val session = request(url, "POST", JSONObject().put("item", JSONObject()
            .put("@microsoft.graph.conflictBehavior", if (existing == null) "fail" else "replace")),
            etag = existing?.etag)
        val uploadUrl = session.getString("uploadUrl")
        require(uploadUrl.startsWith("https://")) { "无效的上传会话" }
        var position = 0L
        var recoveries = 0
        var source = stream()
        var sourcePosition = 0L
        try {
        while (position < size) {
            val length = minOf(CHUNK_SIZE.toLong(), size - position).toInt()
            if (position != sourcePosition) {
                source.close()
                source = stream()
                skipFully(source, position)
                sourcePosition = position
            }
            val bytes = ByteArray(length).also { buffer ->
                    var read = 0
                    while (read < length) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = source.read(buffer, read, minOf(64 * 1024, length - read))
                        check(count > 0) { "本地文件在上传中发生变化，请重试：$name" }
                        read += count
                    }
            }
            sourcePosition += length
            try {
                val req = Request.Builder().url(uploadUrl).put(bytes.toRequestBody(BINARY))
                    .header("Content-Range", "bytes $position-${position + length - 1}/$size").build()
                val reply = execute(req)
                if (reply.code == 200 || reply.code == 201) {
                    onProgress(size)
                    val item = JSONObject(reply.body)
                    return CloudItem(item.getString("id"), item.optString("eTag"), false)
                }
                if (reply.code != 202) throw cloudError(reply, "上传文件")
                val next = JSONObject(reply.body).getJSONArray("nextExpectedRanges").getString(0).substringBefore('-').toLong()
                check(next > position && next <= size) { "上传会话返回了无效进度" }
                position = next
                recoveries = 0
                onProgress(position)
            } catch (error: IOException) {
                if (++recoveries > 3 || (error is CloudError && error.code !in listOf(416, 429, 500, 502, 503, 504))) throw error
                val status = execute(Request.Builder().url(uploadUrl).get().build())
                if (status.code != 200) throw cloudError(status, "查询上传进度")
                position = JSONObject(status.body).getJSONArray("nextExpectedRanges").getString(0).substringBefore('-').toLong()
                onProgress(position)
            }
        }
        throw IOException("上传未收到完成确认，请重试")
        } finally { source.close() }
    }

    private suspend fun request(url: String, method: String = "GET", body: JSONObject? = null,
                                raw: ByteArray? = null, etag: String? = null): JSONObject {
        for (attempt in 0..1) {
            val builder = Request.Builder().url(url).header("Authorization", "Bearer ${token(attempt == 1)}")
            if (!etag.isNullOrEmpty()) builder.header("If-Match", etag)
            val requestBody = raw?.toRequestBody(BINARY) ?: body?.toString()?.toRequestBody(JSON)
            if (method != "GET") builder.method(method, requestBody ?: byteArrayOf().toRequestBody(JSON))
            val reply = execute(builder.build())
            if (reply.code == 401 && attempt == 0) continue
            if (reply.code !in 200..299) throw cloudError(reply, when {
                url.endsWith("/createUploadSession") -> "创建上传会话"
                method == "POST" -> "创建云端目录"
                method == "PUT" -> "上传文件"
                else -> "读取云端项目"
            })
            return if (reply.body.isBlank()) JSONObject() else JSONObject(reply.body)
        }
        throw CloudError(401)
    }
    private data class Reply(val code: Int, val body: String, val retry: Long)
    private fun cloudError(reply: Reply, operation: String): CloudError {
        val error = runCatching { JSONObject(reply.body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
        val message = error?.optString("message").orEmpty()
            .replace(Regex("https?://\\S+"), "[地址已省略]")
            .replace(Regex("[\\r\\n]+"), " ").take(200)
        return CloudError(reply.code, listOfNotNull(operation, code, message.takeIf { it.isNotBlank() }).joinToString(" · "))
    }
    private suspend fun execute(request: Request): Reply {
        repeat(4) { attempt ->
            val reply = http.newCall(request).await().use {
                Reply(it.code, it.body.string(), it.header("Retry-After")?.toLongOrNull() ?: (2L shl attempt))
            }
            if (reply.code !in listOf(429, 500, 502, 503, 504) || attempt == 3) return reply
            delay(reply.retry.coerceIn(1, 60) * 1000)
        }
        error("请求重试失败")
    }
    companion object {
        const val CHUNK_SIZE = 10 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun skipFully(input: InputStream, amount: Long) {
            var remaining = amount
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped else {
                    check(input.read() != -1) { "本地文件长度已变化" }
                    remaining--
                }
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
