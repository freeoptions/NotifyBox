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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.core.*
import io.github.notifybox.data.NotificationRow
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Composable
fun RecordsScreen(vm: AppViewModel, detail: (String) -> Unit) {
    val choices by rememberApplicationChoices(vm)
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
    // 行与解析内容在同一次 Flow 更新中绑定；LazyColumn 不能再用新行查询旧内容表。
    val entries by remember(query) {
        vm.repository.dao.notificationPage(query).map(::recordListItems).flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(emptyList())
    val count by remember(countQuery) { vm.repository.dao.notificationCount(countQuery) }.collectAsStateWithLifecycle(-1)
    val recordIds = entries.map { it.row.id }
    val logs by remember(recordIds) { vm.repository.dao.pageActions(recordIds) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(count) { if (count >= 0 && page > ((count - 1).coerceAtLeast(0) / 20)) page = (count - 1).coerceAtLeast(0) / 20 }
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
        if (entries.isEmpty()) item { EmptyCard("暂无记录", "没有符合条件的通知") }
        items(entries, key = { it.row.id }) { entry ->
            val row = entry.row
            val n = entry.snapshot
            if (n == null) {
                SectionCard("记录内容无法读取") {
                    Text(timeLabel(row.createdAt), style = MaterialTheme.typography.labelMedium)
                    Text("这条记录的格式异常，其他记录仍可查看。")
                }
                return@items
            }
            val recordStatuses = statuses[row.id].orEmpty()
            val appName = targetCaption(ApplicationTarget(n.packageName, n.userId), choices, n.appName)
            SectionCard(appName, onClick = { detail(row.id) },
                titleLeading = { ApplicationIcon(n.packageName, n.appName) }) {
                Text(timeLabel(row.createdAt) + " · " + eventLabel(row.kind), style = MaterialTheme.typography.labelMedium)
                if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall)
                if (n.text.isNotBlank()) Text(n.text, maxLines = 4)
                if (row.matchedNames.isNotBlank()) Text("命中：" + row.matchedNames, color = MaterialTheme.colorScheme.primary)
                if (recordStatuses.isNotEmpty()) Text(recordStatuses.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            }
        }
        item { PageControls(page, count, ::toPage) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetail(vm: AppViewModel, row: NotificationRow, back: () -> Unit, create: () -> Unit) {
    val n = remember(row) { RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson) }
    val choices by rememberApplicationChoices(vm)
    val actions by remember(row.id) { vm.repository.dao.observeActions(row.id) }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember(row.id) { vm.repository.dao.observeTasksForNotification(row.id) }.collectAsStateWithLifecycle(emptyList())
    val process = remember(n, actions, tasks) { recordProcessNodes(n, actions, tasks) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf<List<TrialResult>?>(null) }
    var delete by remember { mutableStateOf(false) }
    Scaffold(topBar = { NotifyTopBar(title = "通知详情", onBack = back) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SectionCard(targetCaption(ApplicationTarget(n.packageName, n.userId), choices, n.appName),
                    titleLeading = { ApplicationIcon(n.packageName, n.appName) }) {
                    if (n.title.isNotBlank()) Text(n.title)
                    if (n.text.isNotBlank()) Text(n.text)
                    if (row.matchedNames.isNotBlank()) Text("命中规则：" + row.matchedNames, color = MaterialTheme.colorScheme.primary)
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
            item { RecordProcessCard(process) }
            item { TextButton(onClick = { delete = true }) { Text("删除这条记录") } }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这条记录？") },
        text = { Text("转发队列会保留，不撤回已发送内容。") },
        confirmButton = { TextButton(onClick = { delete = false; vm.launch("记录已删除", back) { vm.repository.deleteRecord(row.id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable
fun RecordProcessCard(nodes: List<RecordProcessNode>) {
    SectionCard("处理过程") {
        nodes.forEachIndexed { index, node ->
            if (index > 0) HorizontalDivider(color = NotifyColors.line)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = if (node.attention) MaterialTheme.colorScheme.error else NotifyColors.ink)
                if (node.at != null) Text(timeLabel(node.at), style = MaterialTheme.typography.labelMedium, color = NotifyColors.muted)
                if (node.detail.isNotBlank()) Text(node.detail, style = MaterialTheme.typography.bodySmall, color = NotifyColors.muted)
            }
        }
    }
}
