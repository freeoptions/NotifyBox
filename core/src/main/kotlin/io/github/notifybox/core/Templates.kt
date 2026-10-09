package io.github.notifybox.core

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

object Templates {
    // Android 使用 ICU 正则，左右花括号作为字面量都必须转义。
    private val field = Regex("\\{\\{?\\s*([A-Za-z0-9_.]+)\\s*\\}\\}?")
    private fun value(name: String, n: NotificationSnapshot): String = when (name) {
        "android.title" -> n.title
        "android.text" -> n.text
        "android.package", "filterbox.field.PACKAGE_NAME" -> n.packageName
        "android.appName", "filterbox.field.APP_NAME" -> n.appName
        "android.userId" -> n.userId.toString()
        "android.postedAt" -> n.postedAt.toString()
        "android.key" -> n.key
        "filterbox.field.WHEN" -> error("旧 WHEN 格式未确认，请改用 android.postedAt（毫秒）")
        else -> error("未知模板字段：" + name)
    }
    fun plain(template: String, n: NotificationSnapshot): String = field.replace(template) { value(it.groupValues[1], n) }
    fun url(template: String, n: NotificationSnapshot): String {
        val rendered = field.replace(template) { URLEncoder.encode(value(it.groupValues[1], n), "UTF-8").replace("+", "%20") }
        val uri = URI(rendered)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "URL 必须是 HTTPS 且不能含用户名密码" }
        require(!rendered.contains('\r') && !rendered.contains('\n')) { "URL 不能含换行，请在导入时确认规范化" }
        return rendered
    }
    // 解析后只替换字符串节点，再序列化，避免把消息直接拼进 JSON。
    fun json(template: String, n: NotificationSnapshot): String {
        fun replace(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.map { (k, v) -> plain(k, n) to replace(v) }.toMap())
            is JsonArray -> JsonArray(element.map(::replace))
            is JsonPrimitive -> if (element.isString) JsonPrimitive(plain(element.content, n)) else element
        }
        return replace(RuleJson.parseToJsonElement(template)).toString()
    }
    fun validate(kind: ProfileKind, c: WebhookConfig): List<String> = buildList {
        val sample = NotificationSnapshot("key", "sample.package", 0, postedAt = 0, receivedAt = 0)
        try {
            if (kind == ProfileKind.TELEGRAM) {
                require(c.telegramToken.matches(Regex("[0-9]+:[A-Za-z0-9_-]+"))) { "请填写有效 Bot Token" }
                require(c.chatId.isNotBlank()) { "请填写 chat_id" }
                plain(c.messageTemplate, sample)
                require(c.messageTemplate.isNotBlank()) { "请填写消息模板" }
            } else {
                url(c.urlTemplate, sample)
                c.headers.forEach { (k, v) ->
                    require(k.matches(Regex("[A-Za-z0-9-]+"))) { "请求头名称无效" }
                    require(!v.contains('\r') && !v.contains('\n')) { "请求头不能包含换行" }
                    val rendered = plain(v, sample)
                    require(rendered.all { it.code in 32..126 || it == '\t' }) { "请求头只支持 ASCII 字符" }
                }
                if (c.method == HttpMethod.POST) {
                    if (c.jsonBody) json(c.bodyTemplate, sample) else plain(c.bodyTemplate, sample)
                }
            }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            add(if (message.startsWith("旧 WHEN 格式未确认") || message.startsWith("未知模板字段：")) message
                else "转发配置无效，请检查 HTTPS、请求头、JSON、Token 与模板字段")
        }
    }
}
@kotlinx.serialization.Serializable
data class RenderedRequest(val method: HttpMethod, val url: String, val headers: Map<String, String>, val body: String, val jsonBody: Boolean, val telegram: Boolean)
object RequestRenderer {
    fun render(kind: ProfileKind, c: WebhookConfig, n: NotificationSnapshot): RenderedRequest {
        require(Templates.validate(kind, c).isEmpty()) { "转发配置校验失败" }
        if (kind == ProfileKind.TELEGRAM) {
            val text = Templates.plain(c.messageTemplate, n)
            require(text.isNotBlank() && text.codePointCount(0, text.length) <= 4096) { "Telegram 文本需为 1 到 4096 字符" }
            val body = buildJsonObject { put("chat_id", c.chatId); put("text", text) }.toString()
            return RenderedRequest(HttpMethod.POST, "https://api.telegram.org/bot" + c.telegramToken + "/sendMessage", emptyMap(), body, true, true)
        }
        val headers = c.headers.mapValues { Templates.plain(it.value, n) }
        require(headers.values.none { it.contains('\r') || it.contains('\n') || it.any { ch -> ch.code !in 32..126 && ch != '\t' } }) { "渲染后的请求头无效" }
        return RenderedRequest(c.method, Templates.url(c.urlTemplate, n), headers,
            if (c.method == HttpMethod.GET) "" else if (c.jsonBody) Templates.json(c.bodyTemplate, n) else Templates.plain(c.bodyTemplate, n), c.jsonBody, false)
    }
    fun dedupKey(ruleId: String, profileId: String, n: NotificationSnapshot, request: RenderedRequest): String {
        val parts = listOf(ruleId, profileId, n.packageName, n.userId.toString(), request.method.name, request.url,
            request.headers.toSortedMap().toString(), request.body)
        val source = parts.joinToString("") { it.length.toString() + ":" + it }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
