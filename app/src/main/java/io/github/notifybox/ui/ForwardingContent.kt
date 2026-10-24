package io.github.notifybox.ui

import io.github.notifybox.core.RenderedRequest
import io.github.notifybox.core.RuleJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 读取任务实际保存的请求正文，不按当前配置重新渲染，也不展示 URL 或鉴权头。 */
fun forwardingMessage(request: RenderedRequest): String {
    if (!request.telegram) return request.body.ifBlank { "（此请求没有正文）" }
    val body = RuleJson.parseToJsonElement(request.body) as? JsonObject
    val text = body?.get("text") as? JsonPrimitive
    require(text != null && text.isString) { "Telegram 请求缺少文本内容" }
    return text.content.ifBlank { "（转发内容为空）" }
}
