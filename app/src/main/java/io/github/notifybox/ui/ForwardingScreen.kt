package io.github.notifybox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.data.TaskRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import io.github.notifybox.core.RenderedRequest
import io.github.notifybox.core.RuleJson
import kotlinx.serialization.decodeFromString
import io.github.notifybox.R

@Composable
fun ForwardingScreen(vm: AppViewModel, openChannels: () -> Unit) {
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
        item { ForwardingHeader(openChannels) }
        item {
            ListFilterTabs(listOf("" to "全部", "FAILED" to "失败", "SUCCESS" to "成功"), status) {
                status = it; toPage(0)
            }
        }
        if (tasks.isEmpty()) item { Text("暂无发送任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { task ->
            val message by rememberForwardingMessage(vm, task)
            ForwardingTaskCard(task, message, { detailId = task.id }, { retry = task },
                { vm.launch("已暂停重试") { vm.repository.pauseRetry(task.id) } })
        }
        item { PageControls(page, count, ::toPage) }
    }
    detail?.let { task ->
        val message by rememberForwardingMessage(vm, task)
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("发送详情") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message ?: "正在读取转发内容…")
                HorizontalDivider(color = NotifyColors.line)
                Text("配置：" + task.profileName + "\n状态：" + statusLabel(task.status) +
                "\nHTTP：" + (task.httpCode?.toString() ?: "无响应") + "\n尝试次数：" + task.attempts +
                "\n上次尝试：" + (if (task.lastAttemptAt == 0L) "尚未发送" else timeLabel(task.lastAttemptAt)) +
                "\n计划发送：" + timeLabel(task.nextRunAt) + "\n结果摘要：" + task.summary)
            } },
            confirmButton = { TextButton(onClick = { detailId = null }) { Text("关闭") } })
    }
    retry?.let { task ->
        AlertDialog(onDismissRequest = { retry = null }, title = { Text("重试这条任务？") },
            text = { Text(if (task.status == "UNKNOWN") "服务端可能已接收，再次发送可能产生重复消息。" else "使用当前转发配置重试，仍保留在这条记录中。") },
            confirmButton = { TextButton(onClick = { retry = null; vm.launch("任务已重新排队") { vm.repository.retry(task.id) } }) { Text("重试") } },
            dismissButton = { TextButton(onClick = { retry = null }) { Text("取消") } })
    }
}

@Composable
fun ForwardingHeader(openChannels: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { PageIntro("消息转发", "") }
        IconButton(onClick = openChannels) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "转发通道配置",
                tint = NotifyColors.blue, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun ForwardingChannelsScreen(vm: AppViewModel, back: () -> Unit, add: () -> Unit, edit: (String) -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { NotifyTopBar("转发通道", back) }) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().background(NotifyColors.background)) {
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profiles.isEmpty()) item {
                    EmptyCard("还没有转发通道", "点击右下角 + 添加 Telegram 或通用 Webhook，然后在规则中勾选转发并选择通道。")
                }
                items(profiles, key = { it.id }) { profile ->
                    SectionCard(profile.name) {
                        Text((if (profile.kind == "TELEGRAM") "Telegram" else "通用 Webhook") +
                            (if (profile.configCipher.isEmpty()) " · 需要补填" else ""))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { edit(profile.id) }) { Text("编辑") }
                            TextButton(enabled = profile.configCipher.isNotEmpty(), onClick = {
                                vm.launch("测试消息已排队") { vm.repository.testProfile(profile.id) }
                            }) { Text("发送测试消息") }
                        }
                    }
                }
            }
            AddFloatingButton(add, "新增转发通道", Modifier.align(Alignment.BottomEnd).padding(20.dp))
        }
    }
}

@Composable
private fun rememberForwardingMessage(vm: AppViewModel, task: TaskRow): State<String?> {
    // 只为可见项解密，状态和重试次数更新不重复解密；明文不写入数据库或日志。
    return key(task.id, task.requestCipher) {
        produceState<String?>(null) {
            value = withContext(Dispatchers.IO) {
                try {
                    forwardingMessage(RuleJson.decodeFromString<RenderedRequest>(vm.repository.vault.decrypt(task.requestCipher)))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { "转发内容暂时无法读取" }
            }
        }
    }
}

@Composable
fun ForwardingTaskCard(task: TaskRow, message: String?, detail: () -> Unit, retry: () -> Unit, pause: () -> Unit) {
    SectionCard("") {
        Text(message ?: "正在读取转发内容…", style = MaterialTheme.typography.bodyLarge,
            color = NotifyColors.ink, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Text(timeLabel(task.createdAt) + " · " + statusLabel(task.status),
            color = NotifyColors.muted, style = MaterialTheme.typography.labelMedium)
        Text(task.summary, style = MaterialTheme.typography.bodySmall, color = NotifyColors.inkSoft)
        Text("已尝试 ${task.attempts} 次" + if (task.status == "PENDING") " · 下次：" + timeLabel(task.nextRunAt) else "", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = detail) { Text("详情") }
            if (task.status in listOf("FAILED", "UNKNOWN", "PENDING", "CANCELLED")) TextButton(onClick = retry) { Text("立即重试") }
            if (task.status == "PENDING") TextButton(onClick = pause) { Text("暂停") }
        }
    }
}
