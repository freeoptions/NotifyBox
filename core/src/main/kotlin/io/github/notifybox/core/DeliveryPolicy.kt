package io.github.notifybox.core

data class DeliveryOutcome(val status: TaskStatus, val summary: String, val retryAfterSeconds: Long? = null)
object DeliveryPolicy {
    fun retryDelay(attempts: Int): Long = (30L * (1L shl (attempts - 1).coerceIn(0, 6))).coerceAtMost(1800)
    fun retryable(outcome: DeliveryOutcome, attempts: Int, continuous: Boolean): DeliveryOutcome =
        if (continuous && outcome.status == TaskStatus.UNKNOWN)
            outcome.copy(status = TaskStatus.PENDING, summary = outcome.summary + "；将自动重试，可能重复接收", retryAfterSeconds = retryDelay(attempts))
        else if (!continuous && outcome.status == TaskStatus.PENDING && attempts >= 3)
            outcome.copy(status = TaskStatus.FAILED, summary = outcome.summary + "；已尝试 3 次，请手动重试", retryAfterSeconds = null)
        else if (outcome.status == TaskStatus.PENDING)
            outcome.copy(retryAfterSeconds = maxOf(outcome.retryAfterSeconds ?: 0, retryDelay(attempts)))
        else outcome
    fun response(http: Int, telegram: Boolean, telegramOk: Boolean? = null, retryAfterSeconds: Long? = null): DeliveryOutcome {
        if (http == 429 || (telegram && telegramOk == false && retryAfterSeconds != null)) return DeliveryOutcome(
            TaskStatus.PENDING, "服务端限流，稍后重试", (retryAfterSeconds ?: 60).coerceIn(1, 604800))
        if (http in 200..299 && (!telegram || telegramOk == true)) return DeliveryOutcome(TaskStatus.SUCCESS, "发送成功")
        if (http in 200..299 && telegram && telegramOk == null) return DeliveryOutcome(TaskStatus.UNKNOWN, "响应无法确认是否已接收，请决定是否重试")
        // 5xx 也可能已接收，不自动再次发送通知内容。
        if (http >= 500) return DeliveryOutcome(TaskStatus.UNKNOWN, "服务端异常，接收结果未知，请决定是否重试")
        return DeliveryOutcome(TaskStatus.FAILED, when {
            http == 407 -> "代理要求认证，请检查代理配置"
            telegram && http == 401 -> "Bot Token 无效，请检查凭证"
            telegram && http == 403 -> "机器人没有发送权限，或已被接收方屏蔽"
            telegram && http == 400 -> "Telegram 请求无效，请检查聊天 ID 与消息内容"
            telegram && http in 200..299 -> "Telegram 返回业务失败"
            else -> "HTTP 请求失败"
        })
    }
}
