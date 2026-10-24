package io.github.notifybox.ui

import io.github.notifybox.core.NotificationSnapshot
import io.github.notifybox.data.ActionRow
import io.github.notifybox.data.TaskRow
import org.junit.Assert.*
import org.junit.Test

class RecordProcessTest {
    private val n = NotificationSnapshot("key", "sample.app", 0, postedAt = 1000, receivedAt = 429000)
    private fun log(id: String, status: String, at: Long, action: String = "消除", summary: String = "说明") =
        ActionRow(id, "record", "短信转发", action, status, summary, at)
    private fun task(id: String = "task", status: String = "SUCCESS") = TaskRow(id, "record", "rule", "短信转发",
        "profile", "Telegram", "sample.app", 0, "", "", status, 429000, 500000,
        attempts = 1, summary = "发送成功", lastAttemptAt = 429100)

    @Test fun completedNotificationShowsOnlyKeyNodesWithActualTimes() {
        val nodes = recordProcessNodes(n, listOf(
            log("pending", "PENDING", 429000, summary = "已计划延迟 5000 ms 消除"),
            log("delivery.task", "SUCCESS", 429500, "转发", "发送成功"),
            log("request", "REQUESTED", 434000),
            log("removed.record", "REMOVED", 434100, "通知状态"),
            log("confirmation", "REMOVED", 434101),
        ), listOf(task()))
        assertEquals(listOf("通知发布", "NotifyBox 采集", "转发成功", "请求消除", "通知已移除"), nodes.map { it.title })
        assertEquals(listOf(1000L, 429000L, 429500L, 434000L, 434100L), nodes.map { it.at })
        assertFalse(nodes.any { it.detail.contains("5000 ms") || it.title.contains("消除成功") })
    }

    @Test fun userRemovalDoesNotClaimOurDismissalSucceeded() {
        val nodes = recordProcessNodes(n, listOf(
            log("pending", "PENDING", 429000),
            log("cancelled", "CANCELLED", 430000, summary = "通知已被用户、应用或系统移除"),
            log("removed.record", "REMOVED", 430001, "通知状态"),
        ), emptyList())
        assertEquals(listOf("通知发布", "NotifyBox 采集", "通知已移除"), nodes.map { it.title })
    }

    @Test fun missingResultTimeNeverUsesAttemptTimeAsSuccessTime() {
        val node = recordProcessNodes(n, emptyList(), listOf(task())).last()
        assertEquals("转发成功", node.title)
        assertNull(node.at)
        assertTrue(node.detail.contains("最近尝试"))
    }

    @Test fun retryRetainsReasonAndNextAttemptInsteadOfDuplicateStatusRows() {
        val retry = task(status = "PENDING").copy(attempts = 3, summary = "网络连接超时，等待重试")
        val nodes = recordProcessNodes(n, listOf(log("delivery.task", "PENDING", 430000, "转发", retry.summary)), listOf(retry))
        val node = nodes.single { it.title == "等待重试" }
        assertEquals(430000L, node.at)
        assertTrue(node.detail.contains(retry.summary))
        assertTrue(node.detail.contains("已尝试 3 次"))
        assertTrue(node.detail.contains("下次尝试"))
        assertEquals(3, nodes.size)
    }

    @Test fun failedActionWithoutTaskIsNotHiddenByOtherTaskOfSameRule() {
        val nodes = recordProcessNodes(n, listOf(log("config-failure", "FAILED", 429000, "转发", "转发配置缺失")), listOf(task()))
        assertTrue(nodes.any { it.title == "转发失败" && it.detail.contains("转发配置缺失") && it.attention })
        assertTrue(nodes.any { it.title == "转发成功" })
    }

    @Test fun newPlanDoesNotDisplayPreviousCycleRequestOrUnknownResult() {
        val nodes = recordProcessNodes(n, listOf(
            log("request", "REQUESTED", 429000),
            log("unknown", "UNKNOWN", 434000),
            log("new-plan", "PENDING", 435000),
        ), emptyList())
        assertEquals(listOf("通知发布", "NotifyBox 采集", "等待消除"), nodes.map { it.title })
    }

    @Test fun unconfirmedRequestRemainsUnknownWithoutInventedRemoval() {
        val nodes = recordProcessNodes(n, listOf(log("request", "REQUESTED", 434000), log("unknown", "UNKNOWN", 439000)), emptyList())
        assertEquals(listOf("通知发布", "NotifyBox 采集", "请求消除", "尚未确认移除"), nodes.map { it.title })
        assertTrue(nodes.last().attention)
    }

    @Test fun separateDestinationsAndDedupArePreserved() {
        val nodes = recordProcessNodes(n, listOf(log("dedup", "DEDUP", 429200, "转发", "去重窗口内重复")),
            listOf(task(), task("other", "FAILED").copy(profileName = "备用通道", summary = "认证失败")))
        assertEquals(1, nodes.count { it.title == "转发成功" })
        assertEquals(1, nodes.count { it.title == "转发失败" && it.detail.contains("备用通道") })
        assertEquals(1, nodes.count { it.title == "重复转发已跳过" })
    }
}
