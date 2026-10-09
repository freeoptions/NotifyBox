@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.notifybox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.data.TaskRow
import kotlinx.coroutines.launch

@Composable
fun ForwardingScreen(vm: AppViewModel, add: () -> Unit, edit: (String) -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var status by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun toPage(value: Int) { page = value; scope.launch { listState.scrollToItem(0) } }
    val tasks by remember(status, page) { vm.repository.dao.taskPage(status, page * 20) }.collectAsStateWithLifecycle(emptyList())
    val count by remember(status) { vm.repository.dao.taskCount(status) }.collectAsStateWithLifecycle(-1)
    LaunchedEffect(count) { if (count >= 0 && page > (count - 1).coerceAtLeast(0) / 20) page = (count - 1).coerceAtLeast(0) / 20 }
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = tasks.firstOrNull { it.id == detailId }
    var retry by remember { mutableStateOf<TaskRow?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(NotifyColors.background), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("消息转发", "") }
        item { PrimaryAction("新建转发配置", add, showAddIcon = true) }
        if (profiles.isEmpty()) item { EmptyCard("还没有转发配置", "创建 Telegram 或通用 Webhook，然后在规则中添加转发动作。") }
        items(profiles, key = { it.id }) { profile ->
            SectionCard(profile.name) {
                Text((if (profile.kind == "TELEGRAM") "Telegram" else "通用 Webhook") +
                    (if (profile.configCipher.isEmpty()) " · 需要补填" else ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { edit(profile.id) }) { Text("编辑") }
                    TextButton(enabled = profile.configCipher.isNotEmpty(), onClick = { vm.launch("测试消息已排队") { vm.repository.testProfile(profile.id) } }) { Text("发送测试消息") }
                }
            }
        }
        item {
            SectionCard("发送记录") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "全部", "PENDING" to "待发送", "SENDING" to "发送中", "SUCCESS" to "成功", "FAILED" to "失败", "UNKNOWN" to "结果未知", "CANCELLED" to "已暂停").forEach { (s, label) ->
                        FilterChip(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), selected = status == s, onClick = { status = s; toPage(0) }, label = { Text(label) })
                    }
                }
            }
        }
        if (tasks.isEmpty()) item { Text("暂无发送任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { task ->
            SectionCard(task.profileName + " · " + statusLabel(task.status)) {
                Text(task.ruleName + " · " + timeLabel(task.createdAt))
                Text(task.summary)
                Text("已尝试 ${task.attempts} 次" + if (task.status == "PENDING") " · 下次：" + timeLabel(task.nextRunAt) else "", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { detailId = task.id }) { Text("详情") }
                    if (task.status in listOf("FAILED", "UNKNOWN", "PENDING", "CANCELLED")) TextButton(onClick = { retry = task }) { Text("立即重试") }
                    if (task.status == "PENDING") TextButton(onClick = { vm.launch("已暂停重试") { vm.repository.pauseRetry(task.id) } }) { Text("暂停") }
                }
            }
        }
        item { PageControls(page, count, ::toPage) }
    }
    detail?.let { task ->
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("发送详情") },
            text = { Text("配置：" + task.profileName + "\n状态：" + statusLabel(task.status) +
                "\nHTTP：" + (task.httpCode?.toString() ?: "无响应") + "\n尝试次数：" + task.attempts +
                "\n上次尝试：" + (if (task.lastAttemptAt == 0L) "尚未发送" else timeLabel(task.lastAttemptAt)) +
                "\n计划发送：" + timeLabel(task.nextRunAt) + "\n结果摘要：" + task.summary) },
            confirmButton = { TextButton(onClick = { detailId = null }) { Text("关闭") } })
    }
    retry?.let { task ->
        AlertDialog(onDismissRequest = { retry = null }, title = { Text("重试这条任务？") },
            text = { Text(if (task.status == "UNKNOWN") "服务端可能已接收，再次发送可能产生重复消息。" else "使用当前转发配置重试，仍保留在这条记录中。") },
            confirmButton = { TextButton(onClick = { retry = null; vm.launch("任务已重新排队") { vm.repository.retry(task.id) } }) { Text("重试") } },
            dismissButton = { TextButton(onClick = { retry = null }) { Text("取消") } })
    }
}
