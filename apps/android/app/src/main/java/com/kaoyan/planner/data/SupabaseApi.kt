package com.kaoyan.planner.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.kaoyan.planner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

class SupabaseApi(context: Context) {
    private val appContext = context.applicationContext
    private val sessionStore = SecureSessionStore(appContext)
    private val httpClient = CronetHttpClient(appContext)
    private var session: SupabaseSession? = sessionStore.read()

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val currentUserId: String?
        get() = session?.userId

    fun hasSession(): Boolean = session != null

    suspend fun signIn(email: String, password: String): SupabaseSession = withContext(Dispatchers.IO) {
        checkConfiguration()
        val response = execute(
            path = "/auth/v1/token?grant_type=password",
            method = "POST",
            body = JSONObject().put("email", email).put("password", password),
        )
        saveSession(response)
    }

    suspend fun signUp(email: String, password: String): SupabaseSession? = withContext(Dispatchers.IO) {
        checkConfiguration()
        val response = execute(
            path = "/auth/v1/signup",
            method = "POST",
            body = JSONObject().put("email", email).put("password", password),
        )
        if (!response.has("access_token")) return@withContext null
        saveSession(response)
    }

    fun signOut() {
        session = null
        sessionStore.clear()
    }

    suspend fun getArray(path: String): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(request(path, "GET", null, null))
    }

    suspend fun upsert(path: String, rows: JSONArray) = withContext(Dispatchers.IO) {
        request(path, "POST", rows.toString(), "resolution=merge-duplicates,return=minimal")
    }

    suspend fun patchReturning(path: String, row: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(request(path, "PATCH", row.toString(), "return=representation"))
    }

    suspend fun rpc(name: String, parameters: JSONObject): String = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/${queryValue(name)}", "POST", parameters.toString(), "return=representation")
    }

    private fun saveSession(response: JSONObject): SupabaseSession {
        val user = response.optJSONObject("user") ?: throw IllegalStateException("Supabase Auth did not return a user")
        val newSession = SupabaseSession(
            accessToken = response.getString("access_token"),
            refreshToken = response.optString("refresh_token"),
            userId = user.getString("id"),
        )
        session = newSession
        sessionStore.write(newSession)
        return newSession
    }

    private suspend fun request(path: String, method: String, body: String?, prefer: String?): String = withContext(Dispatchers.IO) {
        checkConfiguration()
        val currentSession = session ?: throw IllegalStateException("请先登录 Supabase 账号")
        val response = try {
            executeHttp(path, method, body, currentSession.accessToken, prefer)
        } catch (error: Exception) {
            Log.e("YantuSupabase", "Request failed: $method $path", error)
            throw error
        }
        if (response.first == HttpURLConnection.HTTP_UNAUTHORIZED && currentSession.refreshToken.isNotBlank()) {
            refreshSession(currentSession.refreshToken)
            val refreshedSession = session ?: throw IllegalStateException("Supabase 登录状态已失效，请重新登录")
            return@withContext checkResponse(executeHttp(path, method, body, refreshedSession.accessToken, prefer))
        }
        checkResponse(response)
    }

    private suspend fun refreshSession(refreshToken: String) {
        val response = execute(
            path = "/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            body = JSONObject().put("refresh_token", refreshToken),
        )
        saveSession(response)
    }

    private suspend fun execute(path: String, method: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val (status, text) = try {
            executeHttp(path, method, body?.toString(), bearerToken = null, prefer = null)
        } catch (error: Exception) {
            Log.e("YantuSupabase", "Auth request failed: $method $path", error)
            throw error
        }
        if (status !in 200..299) throw IllegalStateException("Supabase Auth $status: ${errorText(text)}")
        JSONObject(text)
    }

    private fun executeHttp(
        path: String,
        method: String,
        body: String?,
        bearerToken: String?,
        prefer: String?,
    ): Pair<Int, String> {
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val headers = buildMap {
            put("apikey", BuildConfig.SUPABASE_ANON_KEY)
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            bearerToken?.let { put("Authorization", "Bearer $it") }
            prefer?.let { put("Prefer", it) }
        }
        return httpClient.execute(
            url = "$baseUrl$path",
            method = method,
            headers = headers,
            body = body?.toByteArray(Charsets.UTF_8),
        )
    }

    private fun checkResponse(response: Pair<Int, String>): String {
        val (status, text) = response
        if (status !in 200..299) throw IllegalStateException("Supabase API $status: ${errorText(text)}")
        return text
    }

    private fun errorText(raw: String): String = runCatching {
        val json = JSONObject(raw)
        json.optString("msg").ifBlank { json.optString("message") }.ifBlank { raw }
    }.getOrDefault(raw)

    private fun checkConfiguration() {
        check(isConfigured) {
            "尚未配置 Supabase。请在 apps/android/local.properties 中设置 supabase.url 和 supabase.anonKey。"
        }
    }

    private class SecureSessionStore(context: Context) {
        private val preferences = context.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)

        fun read(): SupabaseSession? {
            val encrypted = preferences.getString(KEY_SESSION, null) ?: return null
            return runCatching {
                val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
                val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
                val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
                SupabaseSession(json.getString("accessToken"), json.getString("refreshToken"), json.getString("userId"))
            }.getOrElse {
                clear()
                null
            }
        }

        fun write(session: SupabaseSession) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            val ciphertext = cipher.doFinal(JSONObject()
                .put("accessToken", session.accessToken)
                .put("refreshToken", session.refreshToken)
                .put("userId", session.userId)
                .toString().toByteArray(Charsets.UTF_8))
            val encrypted = cipher.iv + ciphertext
            preferences.edit().putString(KEY_SESSION, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }

        fun clear() {
            preferences.edit().remove(KEY_SESSION).apply()
        }

        private fun getKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            return generator.generateKey()
        }

        companion object {
            private const val KEY_SESSION = "encrypted_session"
            private const val KEY_ALIAS = "yantu_supabase_session_key"
            private const val ANDROID_KEY_STORE = "AndroidKeyStore"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val GCM_IV_LENGTH = 12
            private const val GCM_TAG_LENGTH = 128
        }
    }
}

fun queryValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
