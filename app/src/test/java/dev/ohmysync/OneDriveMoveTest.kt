package dev.ohmysync

import dev.ohmysync.cloud.CloudItem
import dev.ohmysync.cloud.CloudError
import dev.ohmysync.cloud.OneDriveClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OneDriveMoveTest {
    @Test fun `移动到历史目录使用版本条件且不发送删除请求`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"id":"song","eTag":"v2"}""").build())
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            val moved = cloud.move(CloudItem("song", "v1", false), "history", "deleted.bin")
            val request = server.takeRequest()!!
            assertEquals("PATCH", request.method)
            assertEquals("v1", request.headers["If-Match"])
            val body = JSONObject(request.body!!.utf8())
            assertEquals("history", body.getJSONObject("parentReference").getString("id"))
            assertEquals("fail", body.getString("@microsoft.graph.conflictBehavior"))
            assertEquals("v2", moved.etag)
        }
    }
    @Test fun `云端并发修改拒绝移动且不重试强制覆盖`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(412).body("""{"error":{"code":"preconditionFailed"}}""").build())
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            try { cloud.move(CloudItem("song", "v1", false), "history", "deleted.bin"); fail("必须拒绝移动") }
            catch (error: CloudError) { assertEquals(412, error.code) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `无版本标记不能移动`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            try { cloud.move(CloudItem("song", "", false), "history", "deleted.bin"); fail("必须拒绝移动") }
            catch (_: IllegalStateException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
