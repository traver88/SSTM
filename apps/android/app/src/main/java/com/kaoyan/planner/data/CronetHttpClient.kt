package com.kaoyan.planner.data

import android.content.Context
import android.util.Log
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Cronet（BoringSSL）HTTP 适配器。
 *
 * 背景：设备 Conscrypt 栈对 8443 端口公网地址（iizjj.asia 域）的 TLS 握手在公网路径上被选择性重置，
 * 同设备 Chrome（BoringSSL）可正常访问。此客户端把全部 Supabase 请求统一到嵌入式 Cronet，
 * 与 Chrome 使用相同的 TLS 实现与 ClientHello 特征。
 */
class CronetHttpClient(context: Context) {

    private val engine: CronetEngine by lazy {
        CronetEngine.Builder(context.applicationContext).build()
    }

    private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool()

    /**
     * 同步执行一次 HTTP 请求，返回状态码与响应体。
     * 网络层失败抛出 [IOException]，语义与原 HttpURLConnection 实现一致。
     */
    fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): Pair<Int, String> {
        val outcome = AtomicReference<Outcome?>()
        val latch = CountDownLatch(1)
        val request = engine.newUrlRequestBuilder(url, RequestCallback(url, outcome, latch), callbackExecutor)
            .setHttpMethod(method)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .apply {
                if (body != null) setUploadDataProvider(UploadDataProviders.create(body), callbackExecutor)
            }
            .build()
        request.start()
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            val stuckAt = probeRequestStatus(request)
            request.cancel()
            throw IOException("Cronet 请求超时 (stuckAt=${stuckAt ?: "unknown"}): $method $url")
        }
        return when (val result = outcome.get()) {
            is Outcome.Success -> result.status to result.body
            is Outcome.Failure -> throw IOException("Cronet 请求失败 ($method $url): ${result.error}", result.cause)
            null -> throw IOException("Cronet 请求未产生结果: $method $url")
        }
    }

    private sealed interface Outcome {
        data class Success(val status: Int, val body: String) : Outcome
        data class Failure(val error: String, val cause: CronetException?) : Outcome
    }

    /** 超时后查询请求卡住的阶段，用于区分 TCP/TLS 未建立与已连接等待响应。 */
    private fun probeRequestStatus(request: UrlRequest): String? {
        val statusRef = AtomicReference<Int?>()
        val statusLatch = CountDownLatch(1)
        try {
            request.getStatus(object : UrlRequest.StatusListener() {
                override fun onStatus(status: Int) {
                    statusRef.set(status)
                    statusLatch.countDown()
                }
            })
            statusLatch.await(2, TimeUnit.SECONDS)
        } catch (error: Exception) {
            Log.e("YantuCronet", "probeRequestStatus failed", error)
        }
        return statusRef.get()?.let { requestStatusName(it) }
    }

    private fun requestStatusName(status: Int): String = when (status) {
        UrlRequest.Status.INVALID -> "INVALID"
        UrlRequest.Status.IDLE -> "IDLE"
        UrlRequest.Status.WAITING_FOR_STALLED_SOCKET_POOL -> "WAITING_FOR_STALLED_SOCKET_POOL"
        UrlRequest.Status.WAITING_FOR_AVAILABLE_SOCKET -> "WAITING_FOR_AVAILABLE_SOCKET"
        UrlRequest.Status.WAITING_FOR_DELEGATE -> "WAITING_FOR_DELEGATE"
        UrlRequest.Status.WAITING_FOR_CACHE -> "WAITING_FOR_CACHE"
        UrlRequest.Status.DOWNLOADING_PAC_FILE -> "DOWNLOADING_PAC_FILE"
        UrlRequest.Status.RESOLVING_PROXY_FOR_URL -> "RESOLVING_PROXY_FOR_URL"
        UrlRequest.Status.RESOLVING_HOST_IN_PAC_FILE -> "RESOLVING_HOST_IN_PAC_FILE"
        UrlRequest.Status.ESTABLISHING_PROXY_TUNNEL -> "ESTABLISHING_PROXY_TUNNEL"
        UrlRequest.Status.RESOLVING_HOST -> "RESOLVING_HOST"
        UrlRequest.Status.CONNECTING -> "CONNECTING"
        UrlRequest.Status.SSL_HANDSHAKE -> "SSL_HANDSHAKE"
        UrlRequest.Status.SENDING_REQUEST -> "SENDING_REQUEST"
        UrlRequest.Status.WAITING_FOR_RESPONSE -> "WAITING_FOR_RESPONSE"
        UrlRequest.Status.READING_RESPONSE -> "READING_RESPONSE"
        else -> "STATUS_$status"
    }

    private class RequestCallback(
        private val url: String,
        private val outcome: AtomicReference<Outcome?>,
        private val latch: CountDownLatch,
    ) : UrlRequest.Callback() {

        private val bytesReceived = ByteArrayOutputStream()
        private val writableChannel = Channels.newChannel(bytesReceived)

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            request.read(ByteBuffer.allocateDirect(READ_BUFFER_BYTES))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            try {
                writableChannel.write(byteBuffer)
            } catch (error: IOException) {
                throw IllegalStateException("读取响应体失败", error)
            }
            if (byteBuffer.hasRemaining()) {
                throw IllegalStateException("响应体未完整写入缓冲区")
            }
            request.read(ByteBuffer.allocateDirect(READ_BUFFER_BYTES))
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            outcome.compareAndSet(null, Outcome.Success(info.httpStatusCode, bytesReceived.toString("UTF-8")))
            latch.countDown()
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo, error: CronetException) {
            Log.e("YantuCronet", "Cronet request failed: HTTP ${info.httpStatusCode} $url", error)
            outcome.compareAndSet(null, Outcome.Failure(error.message ?: "unknown", error))
            latch.countDown()
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo) {
            outcome.compareAndSet(null, Outcome.Failure("请求被取消", null))
            latch.countDown()
        }
    }

    companion object {
        private const val READ_BUFFER_BYTES = 32 * 1024
        private const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
