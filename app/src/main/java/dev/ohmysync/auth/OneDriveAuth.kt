package dev.ohmysync.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.ohmysync.data.Account
import dev.ohmysync.data.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class AuthRequired(message: String) : Exception(message)

class OneDriveAuth(context: Context, private val http: OkHttpClient) {
    private val secure = SecureStore(context)
    val needsReauth = MutableStateFlow(secure.get("reauth") == "true")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val clientId: String get() = CLIENT_ID
    val account: Account? get() = secure.get("account")?.takeIf { it != "null" && it.isNotBlank() }?.let { json.decodeFromString<Account>(it) }
    fun begin(context: Context) {
        val verifier = random()
        val state = random()
        secure.put("pending", JSONObject().put("verifier", verifier).put("state", state)
            .put("created", System.currentTimeMillis()).put("client", clientId).toString())
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), FLAGS)
        val uri = "$AUTHORITY/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", clientId).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES).appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account").build()
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }
    suspend fun finish(uri: Uri) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(uri.scheme == SCHEME && uri.host == "oauth") { "无效的授权回调" }
            val pending = secure.get("pending")?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
                ?: error("授权已过期，请重新连接")
            require(pending.getString("state") == uri.getQueryParameter("state") &&
                System.currentTimeMillis() - pending.getLong("created") < 10 * 60_000 &&
                pending.getString("client") == clientId) { "授权校验失败，请重新连接" }
            secure.put("pending", "")
            require(uri.getQueryParameter("error") == null) { "微软授权未完成，请重新连接" }
            val code = uri.getQueryParameter("code") ?: error("未收到授权码")
            val tokens = exchange(FormBody.Builder().add("client_id", clientId).add("grant_type", "authorization_code")
                .add("code", code).add("redirect_uri", REDIRECT).add("code_verifier", pending.getString("verifier"))
                .add("scope", SCOPES).build())
            val request = Request.Builder().url("https://graph.microsoft.com/v1.0/me/drive")
                .header("Authorization", "Bearer ${tokens.access}").build()
            val drive = http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "无法读取 OneDrive，请确认账户已开通网盘（${response.code}）" }
                JSONObject(response.body.string())
            }
            val owner = drive.optJSONObject("owner")?.optJSONObject("user")?.optString("displayName")
            secure.put("tokens", json.encodeToString(tokens))
            secure.put("account", json.encodeToString(Account(drive.getString("id"), owner?.takeIf { it.isNotBlank() } ?: "OneDrive 账户",
                drive.optJSONObject("quota")?.optLong("remaining") ?: 0)))
            secure.put("reauth", "false")
            needsReauth.value = false
        }
    }
    suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cached = secure.get("tokens")?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<Tokens>(it) }
                ?: throw AuthRequired("请先连接 OneDrive 账户")
            if (!forceRefresh && cached.expiresAt > System.currentTimeMillis() + 60_000) return@withContext cached.access
            val refreshed = try {
                exchange(FormBody.Builder().add("client_id", clientId).add("grant_type", "refresh_token")
                    .add("refresh_token", cached.refresh).add("scope", SCOPES).build(), cached.refresh)
            } catch (error: AuthRequired) {
                secure.put("reauth", "true")
                needsReauth.value = true
                throw error
            }
            secure.put("tokens", json.encodeToString(refreshed))
            refreshed.access
        }
    }
    suspend fun disconnect() = mutex.withLock {
        secure.put("tokens", "")
        secure.put("account", "null")
        secure.put("pending", "")
        secure.put("reauth", "false")
        needsReauth.value = false
    }
    private fun exchange(body: FormBody, oldRefresh: String = ""): Tokens {
        val request = Request.Builder().url("$AUTHORITY/token").post(body).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code in 400..499 && response.code != 429) throw AuthRequired("登录凭据已失效或应用配置有误，请重新授权（${response.code}）")
                throw java.io.IOException("微软登录服务暂时不可用（${response.code}）")
            }
            val result = JSONObject(response.body.string())
            Tokens(result.getString("access_token"), result.optString("refresh_token", oldRefresh),
                System.currentTimeMillis() + result.getLong("expires_in") * 1000)
        }
    }
    private fun random() = ByteArray(48).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, FLAGS) }
    companion object {
        const val CLIENT_ID = "af01680f-8310-4a44-b712-ba168cec5729"
        const val SCHEME = "dev.ohmysync"
        const val REDIRECT = "$SCHEME://oauth"
        private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
        private const val SCOPES = "offline_access Files.ReadWrite"
        private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
