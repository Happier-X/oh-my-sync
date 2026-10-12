package dev.ohmysync

import dev.ohmysync.cloud.CloudItem
import dev.ohmysync.cloud.OneDriveClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OneDriveReadTest {
    @Test fun `曲库枚举会读取所有分页`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            server.enqueue(reply("""{"value":[{"id":"a","name":"歌曲.flac","eTag":"v1","size":42}],"@odata.nextLink":"${server.url("/drive/next")}"}"""))
            server.enqueue(reply("""{"value":[{"id":"b","name":"封面","eTag":"v2","folder":{}}]}"""))
            val result = cloud.listFiles("parent")
            assertEquals(listOf("歌曲.flac", "封面"), result.map { it.name })
            assertEquals(42L, result.first().size)
            assertTrue(result.last().item.isFolder)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `分页地址不能把令牌带到其他服务器`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            server.enqueue(reply("""{"value":[],"@odata.nextLink":"https://example.org/stolen"}"""))
            try { cloud.listFiles("parent"); fail("必须拒绝跨站分页") } catch (_: IllegalStateException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `下载重定向不附带令牌且前后验证版本`() = runBlocking {
        MockWebServer().use { graph -> MockWebServer().use { storage ->
            graph.start()
            storage.start()
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, graph.url("/drive").toString())
            graph.enqueue(reply("""{"eTag":"v1"}"""))
            graph.enqueue(MockResponse.Builder().code(302).addHeader("Location", storage.url("/song")).build())
            graph.enqueue(reply("""{"eTag":"v1"}"""))
            storage.enqueue(reply("歌曲内容"))
            val output = ByteArrayOutputStream()
            cloud.download(CloudItem("song", "v1", false), output)
            assertEquals("歌曲内容", output.toString("UTF-8"))
            assertNull(storage.takeRequest(5, TimeUnit.SECONDS)!!.headers["Authorization"])
            assertEquals(3, graph.requestCount)
        } }
    }
    @Test fun `下载时云端变化不能确认成功`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            server.enqueue(reply("""{"eTag":"v1"}"""))
            server.enqueue(reply("内容"))
            server.enqueue(reply("""{"eTag":"v2"}"""))
            try { cloud.download(CloudItem("song", "v1", false), ByteArrayOutputStream()); fail("必须发现版本变化") }
            catch (_: IllegalStateException) { }
        }
    }
    private fun reply(body: String) = MockResponse.Builder().code(200).body(body).build()
}
