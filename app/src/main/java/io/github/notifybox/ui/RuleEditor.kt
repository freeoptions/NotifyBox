@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.notifybox.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.core.*
import io.github.notifybox.data.ProfileRow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Composable
fun RuleEditor(vm: AppViewModel, original: Rule, profiles: List<ProfileRow>, back: () -> Unit, isNew: Boolean = false, openProfile: ((String) -> Unit)? = null) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var enabled by rememberSaveable(original.id) { mutableStateOf(original.enabled) }
    val initialApps = remember(original) { RuleJson.encodeToString(original.applications) }
    var appsJson by rememberSaveable(original.id) { mutableStateOf(initialApps) }
    val targets = remember(appsJson) { RuleJson.decodeFromString<List<ApplicationTarget>>(appsJson) }
    val initialKeywords = remember(original) { RuleJson.encodeToString(original.textCondition.values) }
    var keywordsJson by rememberSaveable(original.id) { mutableStateOf(initialKeywords) }
    val keywords = remember(keywordsJson) { RuleJson.decodeFromString<List<String>>(keywordsJson) }
    var keywordInput by rememberSaveable(original.id) { mutableStateOf("") }
    var unrestricted by rememberSaveable(original.id) { mutableStateOf(original.textCondition.values.isEmpty()) }
    var opName by rememberSaveable(original.id) { mutableStateOf(original.textCondition.operator.name) }
    var title by rememberSaveable(original.id) { mutableStateOf(TextField.TITLE in original.textCondition.fields) }
    var text by rememberSaveable(original.id) { mutableStateOf(TextField.TEXT in original.textCondition.fields) }
    var ignoreCase by rememberSaveable(original.id) { mutableStateOf(original.textCondition.ignoreCase) }
    val initialActions = remember(original) { RuleJson.encodeToString(original.actions) }
    var actionsJson by rememberSaveable(original.id) { mutableStateOf(initialActions) }
    var actionRevision by rememberSaveable(original.id) { mutableStateOf(0) }
    val actions = remember(actionsJson) { RuleJson.decodeFromString<List<RuleAction>>(actionsJson) }
    var confirmMigration by rememberSaveable(original.id) { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(0) }
    val loaded by vm.applicationsLoaded.collectAsStateWithLifecycle()
    val choices by rememberApplicationChoices(vm, targets)
    val dirty = name != original.name || enabled != original.enabled || appsJson != initialApps ||
        keywordsJson != initialKeywords || keywordInput.isNotEmpty() || unrestricted != original.textCondition.values.isEmpty() ||
        opName != original.textCondition.operator.name ||
        title != (TextField.TITLE in original.textCondition.fields) || text != (TextField.TEXT in original.textCondition.fields) ||
        ignoreCase != original.textCondition.ignoreCase || actionsJson != initialActions || confirmMigration
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focus = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    PredictiveBackHandler(enabled = (dirty || saving) && !leave && !delete && !picker && !manual && !keyboard) { progress ->
        progress.collect { }
        if (!saving) leave = true
    }
    fun requestBack() {
        if (saving) return
        if (keyboard) { focus.clearFocus(); keyboardController?.hide(); return }
        if (dirty) leave = true else back()
    }
    fun update(index: Int, action: RuleAction) {
        actionsJson = RuleJson.encodeToString(actions.mapIndexed { i, a -> if (i == index) action else a })
    }
    fun save() {
        try {
            val fields = buildSet { if (title) add(TextField.TITLE); if (text) add(TextField.TEXT) }
            // 尚未点击“添加”的关键词也参与保存，避免漏掉最后输入的一项。
            val values = if (unrestricted) emptyList() else keywords + if (keywordInput.isNotEmpty()) listOf(keywordInput) else emptyList()
            val rule = original.copy(name = name.trim(), enabled = enabled, applications = targets,
                textCondition = TextCondition(fields, TextOperator.valueOf(opName), values, ignoreCase), actions = actions)
            val errors = RuleValidator.errors(rule)
            require(errors.isEmpty()) { errors.joinToString("；") }
            require(original.migrationIssues.isEmpty() || confirmMigration) { "请检查并确认导入问题" }
            error = null; saving = true
            vm.launch("规则已保存", after = { saving = false; back() }) {
                try { vm.repository.saveRule(rule, confirmMigration) } catch (e: Exception) { saving = false; throw e }
            }
        } catch (e: IllegalArgumentException) { error = e.message }
    }
    Scaffold(modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { NotifyTopBar(title = if (isNew) "新建规则" else "编辑规则", onBack = ::requestBack) },
        bottomBar = {
            EditorFooter(saving, ::save, if (isNew) "取消" else "删除规则",
                { if (isNew) requestBack() else delete = true }, destructive = !isNew)
        }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            } }
            FormField("规则名称", name, { name = it }, singleLine = true)
            ToggleRow("启用规则", enabled, { enabled = it })
            EditorSection("通知内容", if (targets.isEmpty()) "未选择应用" else "已选择 ${targets.size} 个应用") {
                Text("应用", color = NotifyColors.ink, style = MaterialTheme.typography.titleSmall)
                ApplicationTags(targets, choices, { index ->
                    appsJson = RuleJson.encodeToString(targets.filterIndexed { i, _ -> i != index })
                }, { picker = true })
                HorizontalDivider(color = NotifyColors.line, modifier = Modifier.padding(vertical = 4.dp))
                KeywordEditor(unrestricted, opName, keywords, keywordInput,
                    onMode = { mode -> if (mode == "UNLIMITED") unrestricted = true else { unrestricted = false; opName = mode } },
                    onInput = { keywordInput = it },
                    onAdd = { keywordsJson = RuleJson.encodeToString(keywords + keywordInput); keywordInput = "" },
                    onRemove = { index -> keywordsJson = RuleJson.encodeToString(keywords.filterIndexed { i, _ -> i != index }) },
                    title = title, text = text, onTitle = { title = it }, onText = { text = it })
            }
            EditorSection("规则效果", actionSummary(original.copy(actions = actions))) {
                RuleActionsEditor(original.id, actionRevision, actions, profiles, ::update,
                    onRemove = { index ->
                        actionsJson = RuleJson.encodeToString(actions.filterIndexed { i, _ -> i != index }); actionRevision++
                    },
                    onAdd = { action -> actionsJson = RuleJson.encodeToString(actions + action) }, openProfile = openProfile)
            }
            EditorSection("更多设置", "大小写与用户标识", initiallyExpanded = false) {
                ToggleRow("忽略英文字母大小写", ignoreCase, { ignoreCase = it })
                Text("关键词可分别出现在标题或正文中。未选择应用时不处理任何通知；应用标签未指定用户时匹配该应用的所有用户。",
                    color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { manualInput = formatApplicationTargets(targets); manualError = null; manual = true }) {
                    Text("手动编辑包名与用户标识")
                }
            }
            if (original.migrationIssues.isNotEmpty()) EditorSection("导入待确认", "${original.migrationIssues.size} 项差异") {
                original.migrationIssues.forEach { Text("• " + it, color = MaterialTheme.colorScheme.error) }
                ToggleRow("我已重新配置并接受以上差异", confirmMigration, { confirmMigration = it })
            }
        }
    }
    if (picker) ApplicationPicker(choices, loaded, onDismiss = { picker = false }, onAdd = { added ->
        appsJson = RuleJson.encodeToString((targets + added).distinct()); picker = false
    }, onManual = { picker = false; manualInput = formatApplicationTargets(targets); manualError = null; manual = true })
    if (manual) AlertDialog(onDismissRequest = { manual = false }, title = { Text("手动编辑应用") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField("每行一个包名|用户标识", manualInput, { manualInput = it; manualError = null },
                supporting = "* 表示所有用户，0 为主应用。可添加没有桌面图标的系统应用；留空不处理任何通知。", maxLines = 6)
            manualError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            try { appsJson = RuleJson.encodeToString(parseApplicationTargets(manualInput)); manual = false }
            catch (e: IllegalArgumentException) {
                manualError = if (e is NumberFormatException) "用户标识必须是非负整数或 *" else e.message
            }
        }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { manual = false }) { Text("取消") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除这条规则？") },
        text = { Text("已排队的转发任务会保留。") },
        confirmButton = { TextButton(onClick = {
            delete = false; saving = true
            vm.launch("规则已删除", after = { saving = false; back() }) {
                try { vm.repository.dao.deleteRule(original.id) } catch (e: Exception) { saving = false; throw e }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = { leave = false; back() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("继续编辑") } })
}
