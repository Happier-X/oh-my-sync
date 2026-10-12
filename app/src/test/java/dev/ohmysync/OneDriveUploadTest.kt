package dev.ohmysync

import dev.ohmysync.cloud.CloudItem
import dev.ohmysync.cloud.OneDriveClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class OneDriveUploadTest {
    @Test fun `大文件按合法分块上传且上传地址不附带令牌`() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(certificates.sslSocketFactory())
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
            val cloud = OneDriveClient(http, { "test-token" }, server.url("/drive").toString())
            val size = OneDriveClient.CHUNK_SIZE + 123
            server.enqueue(reply(200, """{"uploadUrl":"${server.url("/upload")}"}"""))
            server.enqueue(reply(202, """{"nextExpectedRanges":["${OneDriveClient.CHUNK_SIZE}-"]}"""))
            server.enqueue(reply(201, """{"id":"file-id","eTag":"v2"}"""))
            var opened = 0
            val progress = mutableListOf<Long>()
            val result = cloud.upload("parent", "照片 空格.bin", size.toLong(), CloudItem("file-id", "v1", false),
                { opened++; ByteArrayInputStream(ByteArray(size)) }, { progress += it })
            assertEquals("v2", result.etag)
            assertEquals(1, opened)
            val session = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer test-token", session.headers["Authorization"])
            assertEquals("v1", session.headers["If-Match"])
            val properties = JSONObject(requireNotNull(session.body).utf8()).getJSONObject("item")
            assertEquals("replace", properties.getString("@microsoft.graph.conflictBehavior"))
            assertFalse(properties.has("name"))
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            val last = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull(first.headers["Authorization"])
            assertNull(last.headers["Authorization"])
            assertEquals("bytes 0-${OneDriveClient.CHUNK_SIZE - 1}/$size", first.headers["Content-Range"])
            assertEquals("bytes ${OneDriveClient.CHUNK_SIZE}-${size - 1}/$size", last.headers["Content-Range"])
            assertEquals(0, OneDriveClient.CHUNK_SIZE % (320 * 1024))
            assertEquals(listOf(OneDriveClient.CHUNK_SIZE.toLong(), size.toLong()), progress)
        }
    }
    @Test fun `没有远端文件时使用失败冲突策略避免覆盖`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cloud = OneDriveClient(OkHttpClient(), { "test-token" }, server.url("/drive").toString())
            server.enqueue(reply(201, """{"id":"empty","eTag":"v1"}"""))
            cloud.upload("parent", "空 文件.txt", 0, null, { ByteArrayInputStream(byteArrayOf()) }, {})
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(request.url.encodedPath.contains("%E7%A9%BA%20%E6%96%87%E4%BB%B6.txt"))
            assertEquals("fail", request.url.queryParameter("@microsoft.graph.conflictBehavior"))
        }
    }
    private fun reply(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()
    @Test fun `分散的临时故障按服务器位置恢复而不累积失败次数`() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(certificates.sslSocketFactory())
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
            val cloud = OneDriveClient(http, { "test-token" }, server.url("/drive").toString())
            server.enqueue(reply(200, """{"uploadUrl":"${server.url("/upload")}"}"""))
            repeat(4) { index ->
                server.enqueue(reply(416, "{}"))
                server.enqueue(reply(200, """{"nextExpectedRanges":["${index.toLong() * OneDriveClient.CHUNK_SIZE}-"]}"""))
                server.enqueue(reply(202, """{"nextExpectedRanges":["${(index + 1L) * OneDriveClient.CHUNK_SIZE}-"]}"""))
            }
            server.enqueue(reply(201, """{"id":"resumed","eTag":"v1"}"""))
            val data = ByteArray(OneDriveClient.CHUNK_SIZE * 4 + 1)
            var opens = 0
            val result = cloud.upload("parent", "大文件.bin", data.size.toLong(), null,
                { opens++; ByteArrayInputStream(data) }, {})
            assertEquals("resumed", result.id)
            assertEquals(5, opens)
            assertEquals(14, server.requestCount)
        }
    }
}
