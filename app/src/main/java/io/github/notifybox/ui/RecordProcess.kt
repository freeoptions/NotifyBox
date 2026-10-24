package io.github.notifybox.ui

import io.github.notifybox.core.NotificationSnapshot
import io.github.notifybox.data.ActionRow
import io.github.notifybox.data.TaskRow

data class RecordProcessNode(val title: String, val at: Long?, val detail: String = "", val attention: Boolean = false)

// 仅归并展示，保留数据库中的原始动作记录。时间使用实际记录，不把发送开始当成成功时间。
fun recordProcessNodes(n: NotificationSnapshot, actions: List<ActionRow>, tasks: List<TaskRow>): List<RecordProcessNode> {
    val logs = actions.sortedBy { it.createdAt }
    val nodes = mutableListOf(
        RecordProcessNode("通知发布", n.postedAt),
        RecordProcessNode("NotifyBox 采集", n.receivedAt),
    )
    tasks.forEach { task ->
        val result = logs.lastOrNull { it.id == "delivery.${task.id}" && it.status == task.status }
        val title = when (task.status) {
            "SUCCESS" -> "转发成功"
            "PENDING" -> if (task.attempts == 0) "等待转发" else "等待重试"
            "SENDING" -> "正在转发"
            "FAILED" -> "转发失败"
            "UNKNOWN" -> "转发结果未知"
            "CANCELLED" -> "转发已暂停"
            else -> "转发状态未知"
        }
        val detail = buildList {
            add(task.profileName.ifBlank { task.ruleName })
            if (task.status !in setOf("SUCCESS", "SENDING")) add(task.summary)
            if (task.attempts > 1) add("已尝试 ${task.attempts} 次")
            if (task.status == "PENDING") add("下次尝试：" + timeLabel(task.nextRunAt))
            // 旧数据可能没有结果时间；只说明最近尝试，不能冒充成功时间。
            if (result == null && task.lastAttemptAt > 0) add("最近尝试：" + timeLabel(task.lastAttemptAt))
        }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
        nodes += RecordProcessNode(title, result?.createdAt ?: task.createdAt.takeIf { task.status == "PENDING" && task.attempts == 0 },
            detail, task.status in setOf("FAILED", "UNKNOWN"))
    }
    // 没有任务的配置失败、跳过和去重仍需可见，不能按规则名称一概隐藏。
    logs.filter { log -> log.action == "转发" && tasks.none { task ->
        log.id == "delivery.${task.id}" || (log.status != "DEDUP" && log.ruleName == task.ruleName &&
            log.status == task.status && log.summary == task.summary)
    } }.groupBy { it.ruleName to (it.status == "DEDUP") }.values.forEach { group ->
        val log = group.last()
        nodes += RecordProcessNode(when (log.status) {
            "DEDUP" -> "重复转发已跳过"
            "FAILED" -> "转发失败"
            "SUCCESS" -> "转发成功"
            "PENDING" -> "等待转发"
            "SENDING" -> "正在转发"
            "CANCELLED" -> "转发已取消"
            "SKIPPED" -> "转发已跳过"
            else -> "转发结果未知"
        }, log.createdAt, listOf(log.ruleName, log.summary).filter { it.isNotBlank() }.distinct().joinToString(" · "),
            log.status in setOf("FAILED", "UNKNOWN"))
    }
    val removed = logs.firstOrNull { it.action == "通知状态" && it.status == "REMOVED" }
        ?: logs.firstOrNull { it.action == "消除" && it.status == "REMOVED" }
    logs.filter { it.action == "消除" }.groupBy { it.ruleName }.values.forEach { group ->
        // 重设计划后，只展示本轮进度，不带入上一次请求。
        val cycle = group.drop(group.indexOfLast { it.status == "PENDING" }.coerceAtLeast(0))
        val latest = cycle.last()
        val requested = cycle.lastOrNull { it.status == "REQUESTED" }
        if (requested != null) nodes += RecordProcessNode("请求消除", requested.createdAt, requested.ruleName)
        val title = when (latest.status) {
            "PENDING" -> if (removed == null) "等待消除" else null
            "REQUESTED", "REMOVED" -> null
            "CANCELLED" -> if (removed != null && latest.summary == "通知已被用户、应用或系统移除") null else "消除已取消"
            "SKIPPED" -> "消除已跳过"
            "STALE" -> "消除已取消：内容更新"
            "MISSING" -> "通知已不在列表"
            "FAILED" -> "消除请求失败"
            "UNKNOWN" -> if (removed == null) "尚未确认移除" else null
            else -> "消除状态未知"
        }
        if (title != null) nodes += RecordProcessNode(title, latest.createdAt,
            if (latest.status == "PENDING") latest.ruleName else
                listOf(latest.ruleName, latest.summary).filter { it.isNotBlank() }.joinToString(" · "),
            latest.status in setOf("FAILED", "UNKNOWN"))
    }
    // 系统移除与消除回调合并成一个节点，不据此断言是本软件移除成功。
    if (removed != null) nodes += RecordProcessNode("通知已移除", removed.createdAt)
    return nodes.sortedBy { it.at ?: Long.MAX_VALUE }
}
