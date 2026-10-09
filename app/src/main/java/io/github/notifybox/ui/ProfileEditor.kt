@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.notifybox.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.notifybox.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@Composable
fun ProfileEditor(vm: AppViewModel, id: String, back: () -> Unit) {
    var meta by remember(id) { mutableStateOf<ProfileMetadata?>(null) }
    var config by remember(id) { mutableStateOf<WebhookConfig?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            if (id == "new") {
                meta = ProfileMetadata(name = "", kind = ProfileKind.TELEGRAM)
                config = WebhookConfig(bodyTemplate = """{"title":"{{android.title}}","text":"{{android.text}}"}""")
            } else {
                withContext(Dispatchers.IO) {
                    val row = vm.repository.dao.profile(id) ?: error("配置不存在")
                    meta = ProfileMetadata(row.id, row.name, ProfileKind.valueOf(row.kind))
                    config = vm.repository.config(id)?.second ?: WebhookConfig(bodyTemplate = """{"title":"{{android.title}}","text":"{{android.text}}"}""")
                }
            }
        } catch (_: Exception) { failed = true }
    }
    when {
        failed -> Column(Modifier.padding(24.dp)) { Text("配置读取失败，请检查加密数据"); TextButton(onClick = back) { Text("返回") } }
        meta != null && config != null -> ProfileForm(vm, meta!!, config!!, back)
        else -> CircularProgressIndicator(Modifier.padding(24.dp))
    }
}
@Composable
private fun ProfileForm(vm: AppViewModel, original: ProfileMetadata, initial: WebhookConfig, back: () -> Unit) {
    // 凭证只保存在页面内存和 Keystore 密文中，不放进 savedInstanceState。
    var name by remember(original.id) { mutableStateOf(original.name) }
    var kind by remember(original.id) { mutableStateOf(original.kind) }
    var method by remember(original.id) { mutableStateOf(initial.method) }
    var url by remember(original.id) { mutableStateOf(initial.urlTemplate) }
    val initialHeaders = remember(initial) { initial.headers.entries.joinToString("\n") { it.key + ": " + it.value } }
    var headers by remember(original.id) { mutableStateOf(initialHeaders) }
    var body by remember(original.id) { mutableStateOf(initial.bodyTemplate) }
    var json by remember(original.id) { mutableStateOf(initial.jsonBody) }
    var token by remember(original.id) { mutableStateOf(initial.telegramToken) }
    var chat by remember(original.id) { mutableStateOf(initial.chatId) }
    var message by remember(original.id) { mutableStateOf(initial.messageTemplate) }
    var revealed by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(0) }
    val dirty = name != original.name || kind != original.kind || method != initial.method || url != initial.urlTemplate ||
        headers != initialHeaders || body != initial.bodyTemplate || json != initial.jsonBody ||
        token != initial.telegramToken || chat != initial.chatId || message != initial.messageTemplate
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focus = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    PredictiveBackHandler(enabled = (dirty || saving) && !leave && !keyboard) { progress ->
        progress.collect { }
        if (!saving) leave = true
    }
    fun requestBack() {
        if (saving) return
        if (keyboard) { focus.clearFocus(); keyboardController?.hide(); return }
        if (dirty) leave = true else back()
    }
    fun save() {
        try {
            val entries = headers.lineSequence().filter { it.isNotBlank() }.map { line ->
                val i = line.indexOf(':')
                require(i > 0) { "请求头每行格式为 Name: value" }
                line.take(i).trim() to line.drop(i + 1).trim()
            }.toList()
            require(entries.map { it.first.lowercase() }.distinct().size == entries.size) { "请求头名称不能重复" }
            val config = WebhookConfig(method, url.trim(), entries.toMap(), body, json, token.trim(), chat.trim(), message)
            val errors = Templates.validate(kind, config)
            require(errors.isEmpty()) { errors.joinToString("；") }
            saving = true; error = null
            vm.launch("转发配置已保存", after = { saving = false; back() }) {
                try { vm.repository.saveProfile(original.copy(name = name.trim(), kind = kind), config) }
                catch (e: Exception) { saving = false; throw e }
            }
        } catch (_: Exception) { error = "请检查名称、凭证、HTTPS 地址、请求头和正文格式。" }
    }
    Scaffold(modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { NotifyTopBar(title = "转发配置", onBack = ::requestBack) },
        bottomBar = { EditorFooter(saving, ::save, "取消", ::requestBack) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            } }
            FormField("配置名称", name, { name = it }, singleLine = true)
            EditorSection("转发方式", if (kind == ProfileKind.TELEGRAM) "Telegram" else "通用 Webhook") {
                ChoiceMenu("发送到", kind, listOf(ProfileKind.TELEGRAM to "Telegram", ProfileKind.GENERIC to "通用 Webhook"), { kind = it })
                Text("保存后，在规则的“转发消息”动作中选择此配置。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
            }
            if (kind == ProfileKind.TELEGRAM) {
                EditorSection("Telegram 设置", "机器人与接收聊天") {
                    OutlinedTextField(token, { token = it }, label = { Text("Bot Token") }, singleLine = true,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "隐藏" else "显示") } })
                    FormField("接收聊天 ID（chat_id）", chat, { chat = it }, singleLine = true)
                    Text("群组 ID 可带负号。机器人需有发送消息的权限。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
                }
                EditorSection("消息内容", "通知标题与正文") {
                    FormField("消息模板", message, { message = it })
                    TemplateInsertButtons { message += it }
                }
            } else {
                EditorSection("Webhook 设置", method.name + " 请求") {
                    FormField("HTTPS 地址", url, { url = it })
                    ChoiceMenu("请求方式", method, HttpMethod.entries.map { it to it.name }, { method = it })
                    Text("地址中的模板字段会自动编码。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
                }
                EditorSection("请求头", if (headers.isBlank()) "未设置" else "已设置请求头", initiallyExpanded = headers.isNotBlank()) {
                    FormField("每行一个 Name: value", headers, { headers = it })
                }
                if (method == HttpMethod.POST) EditorSection("请求内容", if (json) "JSON 正文" else "纯文本正文") {
                    ToggleRow("使用 JSON 格式", json, { json = it })
                    FormField("请求正文模板", body, { body = it })
                    Text("JSON 模板字段应写在字符串值中，例如 \"text\":\"{{android.text}}\"。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            EditorSection("模板字段与说明", "标题、正文、应用名称等", initiallyExpanded = false) {
                Text("{{android.title}} · 标题\n{{android.text}} · 正文\n{{android.package}} · 包名\n{{android.appName}} · 应用名\n{{android.userId}} · 用户\n{{android.postedAt}} · 毫秒时间戳")
                Text("兼容 APP_NAME、PACKAGE_NAME 别名及单花括号。WHEN 格式未确认，不自动替换。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("放弃未保存的配置？") },
        confirmButton = { TextButton(onClick = { leave = false; back() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("继续编辑") } })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TemplateInsertButtons(onInsert: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AddTag("标题", { onInsert("{{android.title}}") })
        AddTag("正文", { onInsert("{{android.text}}") })
        AddTag("应用名称", { onInsert("{{android.appName}}") })
    }
}
