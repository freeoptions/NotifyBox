package io.github.notifybox.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.notifybox.appGraph
import io.github.notifybox.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import java.security.cert.CertificateException

class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var claimedAttempt: Int? = null
    override suspend fun doWork(): Result {
        try { return deliver() } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                val repo = applicationContext.appGraph.repository
                val id = inputData.getString("taskId")
                val task = id?.let { repo.dao.task(it) }
                if (task?.status == TaskStatus.SENDING.name && task.attempts == claimedAttempt) {
                    val outcome = DeliveryPolicy.retryable(DeliveryOutcome(TaskStatus.UNKNOWN, "后台发送中断，接收结果未知"), task.attempts, repo.continuousRetry())
                    val updated = task.copy(status = outcome.status.name, nextRunAt = System.currentTimeMillis() + (outcome.retryAfterSeconds ?: 0) * 1000, summary = outcome.summary)
                    repo.dao.updateTask(updated); repo.deliveryAction(updated, updated.status, updated.summary)
                    if (updated.status == "PENDING") QueueScheduler.schedule(applicationContext, updated.id, updated.nextRunAt)
                }
            }
            throw e
        }
    }
    private suspend fun deliver(): Result = withContext(Dispatchers.IO) {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val repo = graph.repository
        val id = inputData.getString("taskId") ?: return@withContext Result.failure()
        val current = repo.dao.task(id) ?: return@withContext Result.success()
        if (current.status != TaskStatus.PENDING.name) return@withContext Result.success()
        if (current.nextRunAt > System.currentTimeMillis()) {
            return@withContext Result.retry()
        }
        val task = withContext(NonCancellable) {
            if (repo.dao.claim(id, System.currentTimeMillis()) == 0) null
            else repo.dao.task(id)?.also { claimedAttempt = it.attempts }
        } ?: return@withContext Result.success()
        repo.deliveryAction(task, task.status, "正在发送 · 第 ${task.attempts} 次")
        var httpCode: Int? = null
        val requestStarted = AtomicBoolean(false)
        val proxyAddress = repo.proxyAddress()
        val outcome = try {
            val rendered = RuleJson.decodeFromString<RenderedRequest>(repo.vault.decrypt(task.requestCipher))
            val request = Request.Builder().url(rendered.url).apply {
                rendered.headers.forEach { (name, value) -> header(name, value) }
                if (rendered.method == HttpMethod.GET) get()
                else post(rendered.body.toRequestBody((if (rendered.jsonBody) "application/json; charset=utf-8" else "text/plain; charset=utf-8").toMediaType()))
            }.build()
            val builder = client.newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun requestHeadersStart(call: okhttp3.Call) { requestStarted.set(true) }
            })
            if (proxyAddress.isNotBlank()) {
                val address = URI(proxyAddress)
                builder.proxy(Proxy(if (address.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP,
                    InetSocketAddress(address.host, address.port)))
            }
            val sendingClient = builder.build()
            awaitResponse(sendingClient.newCall(request)).use { response ->
                httpCode = response.code
                // 限制响应读取；不存储正文、URL、鉴权头或服务器原始错误描述。
                val body = response.peekBody(16 * 1024).string()
                val json = runCatching { RuleJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                val ok = (json?.get("ok") as? JsonPrimitive)?.booleanOrNull
                val retryHeader = response.header("Retry-After")?.let { header ->
                    header.toLongOrNull() ?: runCatching {
                        ((ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                    }.getOrNull()
                }
                val retryApi = ((json?.get("parameters") as? JsonObject)?.get("retry_after") as? JsonPrimitive)?.longOrNull
                DeliveryPolicy.response(response.code, rendered.telegram, ok, retryApi ?: retryHeader)
            }
        } catch (e: CancellationException) {
            // 取消期间可能已发送；进程重启会将残留 SENDING 恢复为 UNKNOWN。
            throw e
        } catch (_: UnknownHostException) {
            DeliveryOutcome(TaskStatus.PENDING, "DNS 不可达，等待网络", 60)
        } catch (_: ConnectException) {
            DeliveryOutcome(TaskStatus.PENDING, "连接未建立，等待网络", 60)
        } catch (_: SSLPeerUnverifiedException) {
            DeliveryOutcome(TaskStatus.FAILED, "服务器证书验证失败，请检查代理与证书")
        } catch (e: SSLHandshakeException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException })
                DeliveryOutcome(TaskStatus.FAILED, "服务器证书验证失败，请检查代理与证书")
            else DeliveryOutcome(TaskStatus.PENDING, "安全连接握手中断，请检查代理节点", 60)
        } catch (_: SocketTimeoutException) {
            if (requestStarted.get()) DeliveryOutcome(TaskStatus.UNKNOWN, "发送或等待响应超时，接收结果未知")
            else DeliveryOutcome(TaskStatus.PENDING, if (proxyAddress.isBlank()) "网络连接超时，请检查 VPN 分流与节点" else "代理连接超时，请检查代理地址与节点", 60)
        } catch (_: IOException) {
            if (requestStarted.get()) DeliveryOutcome(TaskStatus.UNKNOWN, "连接中断，接收结果未知")
            else DeliveryOutcome(TaskStatus.PENDING, "请求尚未发送，网络连接中断，请检查代理", 60)
        } catch (_: Exception) {
            DeliveryOutcome(TaskStatus.FAILED, "请求配置或加密数据不可用，请检查配置")
        }
        val result = DeliveryPolicy.retryable(outcome, task.attempts, repo.continuousRetry())
        val now = System.currentTimeMillis()
        val updated = task.copy(status = result.status.name, httpCode = httpCode,
            nextRunAt = now + (result.retryAfterSeconds ?: 0) * 1000, summary = result.summary)
        repo.dao.updateTask(updated)
        repo.deliveryAction(updated, updated.status, updated.summary)
        if (result.status != TaskStatus.SUCCESS) DeliveryAlerts.failed(applicationContext)
        if (result.status == TaskStatus.PENDING) QueueScheduler.schedule(applicationContext, id, updated.nextRunAt)
        Result.success()
    }
    private suspend fun awaitResponse(call: okhttp3.Call): okhttp3.Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appGraph.ready.await()
        applicationContext.appGraph.repository.prune()
        return Result.success()
    }
}
