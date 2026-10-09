package io.github.notifybox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.notifybox.core.ApplicationTarget
import io.github.notifybox.core.WebhookAction
import io.github.notifybox.data.ProfileRow

// 供 Android Studio 静态预览使用。示例数据不会写入实际规则或数据库。
@Preview(showBackground = true, widthDp = 393, heightDp = 700, name = "规则页面组件")
@Composable
private fun RulesPreview() {
    NotifyBoxTheme {
        Surface(color = NotifyColors.background) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PageIntro("通知规则", "1 条已启用 · 按应用和文本匹配")
                PrimaryAction("新建规则", {}, showAddIcon = true)
                SectionCard("短信转发", enabledState = true) {
                    Text("短信 · 主应用")
                    Text("排除系统运行提示")
                    Text("Telegram 转发 · 去重 5 秒", color = MaterialTheme.colorScheme.primary)
                    ToggleRow("启用规则", true, {})
                    Row { TextButton(onClick = {}) { Text("编辑") }; TextButton(onClick = {}) { Text("试跑") } }
                }
                EmptyCard("本地运行", "转发任务先写入队列，消除不会取消转发。")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 720, name = "禁用、错误及空状态")
@Composable
private fun StatesPreview() {
    NotifyBoxTheme {
        Surface(color = NotifyColors.background) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PageIntro("通知规则", "示例状态 · 静态预览")
                SectionCard("未启用的规则", enabledState = false) {
                    Text("com.example.app · 主应用")
                    Text("延迟 4000 ms 消除")
                    ToggleRow("启用规则", false, {})
                }
                SectionCard("导入待确认") {
                    Text("文本匹配字段尚未确认，请重新选择。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {}) { Text("检查配置") }
                }
                FormField("应用包名", "com.example.application.long.package.name", {})
                EmptyCard("暂无发送任务", "命中已启用的转发规则后，任务会显示在这里。")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 900, name = "标签与分区规则编辑")
@Preview(showBackground = true, widthDp = 320, heightDp = 800, fontScale = 1.3f, name = "窄屏与大字号规则编辑")
@Composable
private fun ReferenceRuleEditorPreview() {
    NotifyBoxTheme {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { NotifyTopBar("编辑规则", {}) },
            bottomBar = { EditorFooter(false, {}, "删除规则", {}, destructive = true) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FormField("规则名称", "短信转发至 Telegram", {}, singleLine = true)
                ToggleRow("启用规则", false, {})
                EditorSection("通知内容", "3 个应用") {
                    ApplicationTags(listOf(ApplicationTarget("com.example.sms"), ApplicationTarget("com.example.chat", 999),
                        ApplicationTarget("com.example.download")),
                        listOf(AppChoice("com.example.sms", "短信"), AppChoice("com.example.chat", "聊天"),
                            AppChoice("com.example.download", "系统更新与下载管理")), {}, {})
                    HorizontalDivider(color = NotifyColors.line)
                    KeywordEditor(false, "NOT_CONTAINS", listOf("点按即可了解详情或停止应用", "正在后台运行"), "",
                        {}, {}, {}, {}, true, true, {}, {})
                }
                EditorSection("规则效果", "转发消息") {
                    RuleActionsEditor("preview", 0, listOf(WebhookAction("preview-profile")),
                        listOf(ProfileRow("preview-profile", "我的消息机器人", "TELEGRAM", "preview-placeholder")), { _, _ -> }, {}, {})
                }
                EditorSection("更多设置", "大小写与用户标识", initiallyExpanded = false) {
                    ToggleRow("忽略英文字母大小写", true, {})
                }
            }
        }
    }
}
