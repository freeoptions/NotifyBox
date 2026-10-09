package io.github.notifybox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.core.*
import kotlinx.serialization.decodeFromString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialScreen(vm: AppViewModel, rule: Rule, back: () -> Unit) {
    val rows by vm.records.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("") }
    val snapshots = remember(rows) { rows.associate { it.id to RuleJson.decodeFromString<NotificationSnapshot>(it.snapshotJson) } }
    val result = remember(selected, snapshots, rule) { snapshots[selected]?.let { RuleEngine().trial(listOf(rule), it).single() } }
    Scaffold(topBar = { NotifyTopBar(title = "规则试跑", onBack = back) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageIntro(rule.name, "选择历史通知，仅检查匹配；不消除、不发送网络请求。关闭的规则也可试跑。") }
            result?.let { r -> item { SectionCard(if (r.matched) "这条通知会命中" else "这条通知不会命中") {
                if (r.issues.isNotEmpty()) Text(r.issues.joinToString("\n"), color = MaterialTheme.colorScheme.error)
                else Text(actionSummary(rule))
            } } }
            if (rows.isEmpty()) item { EmptyCard("没有可用通知", "请先授权通知访问，并接收一条通知。") }
            items(rows, key = { it.id }) { row ->
                val n = snapshots.getValue(row.id)
                OutlinedCard(onClick = { selected = row.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        RadioButton(selected == row.id, { selected = row.id })
                        Column(Modifier.weight(1f)) {
                            Text(n.appName + " · " + timeLabel(row.createdAt))
                            Text(n.title.ifEmpty { "（标题为空）" })
                            Text(n.text, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
