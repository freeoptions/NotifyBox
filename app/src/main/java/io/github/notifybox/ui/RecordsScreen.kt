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
import io.github.notifybox.core.*
import io.github.notifybox.data.NotificationRow
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.launch

@Composable
fun RecordsScreen(vm: AppViewModel, detail: (String) -> Unit) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var app by rememberSaveable { mutableStateOf("") }
    var rule by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun toPage(value: Int) { page = value; scope.launch { listState.scrollToItem(0) } }
    val codes = remember(status) { listOf("PENDING", "SENDING", "SUCCESS", "FAILED", "UNKNOWN", "DEDUP", "SKIPPED", "CANCELLED", "STALE", "REQUESTED", "REMOVED", "MISSING").filter { statusLabel(it).contains(status, true) } }
    val query = remember(keyword, app, rule, status, page) { vm.repository.recordQuery(keyword, app, rule, codes, status.isNotBlank(), page) }
    val countQuery = remember(keyword, app, rule, status) { vm.repository.recordQuery(keyword, app, rule, codes, status.isNotBlank(), 0, count = true) }
    val rows by remember(query) { vm.repository.dao.notificationPage(query) }.collectAsStateWithLifecycle(emptyList())
    val count by remember(countQuery) { vm.repository.dao.notificationCount(countQuery) }.collectAsStateWithLifecycle(-1)
    val logs by remember(rows.map { it.id }) { vm.repository.dao.pageActions(rows.map { it.id }) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(count) { if (count >= 0 && page > ((count - 1).coerceAtLeast(0) / 20)) page = (count - 1).coerceAtLeast(0) / 20 }
    val snapshots = remember(rows) { rows.associate { it.id to RuleJson.decodeFromString<NotificationSnapshot>(it.snapshotJson) } }
    val statuses = remember(logs) { logs.groupBy { it.notificationId }.mapValues { (_, values) -> values.distinctBy { it.ruleName to it.action }.map { statusLabel(it.status) }.distinct() } }
    LazyColumn(Modifier.fillMaxSize().background(NotifyColors.background), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("通知记录", "") }
        item { FormField("搜索标题或正文", keyword, { keyword = it; toPage(0) }, singleLine = true) }
        item { TextButton(onClick = { filters = !filters }) { Text(if (filters) "收起筛选" else "按应用、规则和状态筛选") } }
        if (filters) item {
            SectionCard("筛选条件") {
                FormField("应用名称、拼音或包名", app, { app = it; toPage(0) }, singleLine = true)
                FormField("命中规则名称", rule, { rule = it; toPage(0) }, singleLine = true)
                FormField("执行状态，例如成功、失败、待处理", status, { status = it; toPage(0) }, singleLine = true)
                TextButton(onClick = { app = ""; rule = ""; status = ""; keyword = ""; toPage(0) }) { Text("清除筛选") }
            }
        }
        if (rows.isEmpty()) item { EmptyCard("暂无记录", "没有符合条件的通知") }
        items(rows, key = { it.id }) { row ->
            val n = snapshots.getValue(row.id)
            SectionCard(n.appName + " · " + eventLabel(row.kind), onClick = { detail(row.id) }) {
                Text(timeLabel(row.createdAt), style = MaterialTheme.typography.labelMedium)
                Text(n.title.ifEmpty { "（标题为空）" }, style = MaterialTheme.typography.titleSmall)
                Text(n.text.ifEmpty { "（正文为空）" }, maxLines = 4)
                if (row.matchedNames.isNotBlank()) Text("命中：" + row.matchedNames, color = MaterialTheme.colorScheme.primary)
                if (statuses[row.id].orEmpty().isNotEmpty()) Text(statuses[row.id]!!.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            }
        }
        item { PageControls(page, count, ::toPage) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetail(vm: AppViewModel, row: NotificationRow, back: () -> Unit, create: () -> Unit) {
    val n = remember(row) { RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson) }
    val actions by remember(row.id) { vm.repository.dao.observeActions(row.id) }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember(row.id) { vm.repository.dao.observeTasksForNotification(row.id) }.collectAsStateWithLifecycle(emptyList())
    val visibleActions = remember(actions, tasks) { actions.filter { log ->
        log.action != "转发" || log.status == "DEDUP" || tasks.none { it.ruleName == log.ruleName }
    } }
    val rules by vm.rules.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf<List<TrialResult>?>(null) }
    var delete by remember { mutableStateOf(false) }
    Scaffold(topBar = { NotifyTopBar(title = "通知详情", onBack = back) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SectionCard(n.appName + " · " + eventLabel(row.kind)) {
                    Text(n.title.ifEmpty { "（标题为空）" })
                    Text(n.text.ifEmpty { "（正文为空）" })
                    Text("收到：" + timeLabel(n.receivedAt) + "\n发送：" + timeLabel(n.postedAt))
                    if (row.matchedNames.isNotBlank()) Text("命中：" + row.matchedNames)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), onClick = create) { Text("创建规则") }
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), onClick = { results = RuleEngine().trial(rules, n) }) { Text("试跑全部规则") }
                }
            }
            results?.let { trial ->
                item { SectionCard("试跑结果 · 不执行动作") {
                    if (trial.isEmpty()) Text("还没有规则")
                    trial.forEach { r -> Text(r.name + "：" + (if (r.matched) "匹配" else "不匹配") + if (r.issues.isNotEmpty()) " · " + r.issues.joinToString("；") else "") }
                } }
            }
            item { Text("动作记录", style = MaterialTheme.typography.titleMedium) }
            if (visibleActions.isEmpty() && tasks.isEmpty()) item { Text("没有动作记录") }
            items(visibleActions, key = { it.id }) { log ->
                SectionCard(if (log.ruleName.isBlank()) log.action else log.ruleName + " · " + log.action) {
                    Text(statusLabel(log.status) + " · " + timeLabel(log.createdAt))
                    Text(log.summary)
                }
            }
            if (tasks.isNotEmpty()) item { SectionCard("转发当前状态") {
                tasks.forEach { task ->
                    Text(task.profileName + " · " + statusLabel(task.status) + "\n" + task.summary)
                    Text("已尝试 ${task.attempts} 次" +
                        (if (task.lastAttemptAt == 0L) "" else " · 上次：" + timeLabel(task.lastAttemptAt)) +
                        (if (task.status == "PENDING") "\n下次：" + timeLabel(task.nextRunAt) else ""), style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { TextButton(onClick = { delete = true }) { Text("删除这条记录") } }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这条记录？") },
        text = { Text("转发队列会保留，不撤回已发送内容。") },
        confirmButton = { TextButton(onClick = { delete = false; vm.launch("记录已删除", back) { vm.repository.deleteRecord(row.id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}
