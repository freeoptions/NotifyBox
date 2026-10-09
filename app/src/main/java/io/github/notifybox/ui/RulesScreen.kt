package io.github.notifybox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RulesScreen(vm: AppViewModel, add: () -> Unit, edit: (String) -> Unit, trial: (String) -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val choices by rememberApplicationChoices(vm)
    var query by rememberSaveable { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val names = remember(choices) { choices.associate { it.packageName to it.name } }
    val profileNames = remember(profiles) { profiles.associate { it.id to it.name } }
    val filtered = remember(rules, names, query) { rules.filter { rule ->
        SearchIndex.matches(rule.name, query) || SearchIndex.matches(ruleSummary(rule), query) ||
            rule.applications.any { SearchIndex.matches(names[it.packageName] ?: it.packageName, query) }
    } }
    LazyColumn(Modifier.fillMaxSize().background(NotifyColors.background), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("通知规则", "") }
        item {
            PrimaryAction("新建规则", add, showAddIcon = true)
        }
        item { FormField("搜索名称、拼音或包名", query, { query = it }, singleLine = true) }
        if (filtered.isEmpty()) item { EmptyCard(if (rules.isEmpty()) "还没有规则" else "没有匹配结果", "新建规则，或到设置导入 JSON。新规则默认关闭。") }
        items(filtered, key = { it.id }) { rule ->
            SectionCard(rule.name, enabledState = rule.enabled) {
                RuleOverview(rule, choices, profileNames)
                if (rule.migrationIssues.isNotEmpty()) Text("导入待确认 · " + rule.migrationIssues.size + " 项", color = MaterialTheme.colorScheme.error)
                ToggleRow("启用规则", rule.enabled, { enabled -> vm.launch { vm.repository.setEnabled(rule.id, enabled) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { edit(rule.id) }) { Text("编辑") }
                    TextButton(onClick = { trial(rule.id) }) { Text("试跑") }
                    TextButton(onClick = { deleteId = rule.id }) { Text("删除") }
                }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除这条规则？") },
        text = { Text("已排队的转发任务会保留。") },
        confirmButton = { TextButton(onClick = { val id = deleteId!!; deleteId = null; vm.launch("规则已删除") { vm.repository.dao.deleteRule(id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
}
