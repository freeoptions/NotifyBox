@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.notifybox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.notifybox.core.*
import io.github.notifybox.data.ProfileRow
import java.math.BigDecimal

@Composable
fun ApplicationTags(targets: List<ApplicationTarget>, choices: List<AppChoice>, onRemove: (Int) -> Unit, onAdd: () -> Unit) {
    val names = remember(choices) { choices.associate { it.packageName to it.name } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.forEachIndexed { index, target ->
            val name = names[target.packageName] ?: target.packageName
            RemovableTag(targetCaption(target, name), { onRemove(index) }) { ApplicationIcon(target.packageName, name) }
        }
        AddTag("添加应用", onAdd)
    }
    if (targets.isEmpty()) Text("未选择应用，不处理任何通知", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun KeywordEditor(
    unrestricted: Boolean, opName: String, keywords: List<String>, input: String,
    onMode: (String) -> Unit, onInput: (String) -> Unit, onAdd: () -> Unit, onRemove: (Int) -> Unit,
    title: Boolean, text: Boolean, onTitle: (Boolean) -> Unit, onText: (Boolean) -> Unit,
) {
    ChoiceMenu("关键词", if (unrestricted) "UNLIMITED" else opName,
        listOf("UNLIMITED" to "所有内容", "ANY" to "包含任一", "ALL" to "包含全部",
            "NOT_CONTAINS" to "不包含任一", "CONTAINS" to "包含一个", "REGEX" to "正则任一"), onMode)
    if (!unrestricted) {
        Text(when (TextOperator.valueOf(opName)) {
            TextOperator.NOT_CONTAINS -> "不包含下面的任一关键词时匹配"
            TextOperator.ALL -> "每个关键词都出现时匹配"
            TextOperator.REGEX -> "符合下面任一正则表达式时匹配"
            else -> "包含下面的关键词时匹配"
        }, color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            keywords.forEachIndexed { index, keyword -> RemovableTag(keyword, { onRemove(index) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, onInput, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (opName == "REGEX") "输入正则表达式…" else "输入关键词…") },
                shape = RoundedCornerShape(12.dp))
            TextButton(enabled = input.isNotEmpty(), onClick = onAdd) { Text("添加") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("查找范围", Modifier.align(Alignment.CenterVertically), color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
            FilterChip(selected = title, onClick = { onTitle(!title) }, label = { Text("标题") })
            FilterChip(selected = text, onClick = { onText(!text) }, label = { Text("正文") })
        }
        if (keywords.isEmpty() && input.isEmpty()) Text("未添加关键词时匹配所有内容。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
        if (opName == "REGEX") Text("RE2 正则，不支持环视和反向引用。", color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RuleActionsEditor(
    ruleId: String, revision: Int, actions: List<RuleAction>, profiles: List<ProfileRow>,
    onUpdate: (Int, RuleAction) -> Unit, onRemove: (Int) -> Unit, onAdd: (RuleAction) -> Unit,
    openProfile: ((String) -> Unit)? = null,
) {
    if (actions.isEmpty()) Text("选择匹配通知后要做的事。", color = NotifyColors.muted)
    actions.forEachIndexed { index, action ->
        key(revision, index) {
            Surface(color = NotifyColors.background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (action is DismissAction) "消除通知" else "转发消息", Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold, color = NotifyColors.ink)
                        TextButton(onClick = { onRemove(index) }) { Text("移除") }
                    }
                    when (action) {
                        is DismissAction -> {
                            var delayed by rememberSaveable(ruleId, revision, index, "delayed") { mutableStateOf(action.delayMs != 0L) }
                            var seconds by rememberSaveable(ruleId, revision, index, "seconds") { mutableStateOf(secondsLabel(action.delayMs)) }
                            ChoiceMenu("消除时机", delayed, listOf(false to "立即消除", true to "延迟消除"), { value ->
                                delayed = value
                                if (value && millisecondsFromSeconds(seconds) <= 0) seconds = secondsLabel(DismissAction().delayMs)
                                onUpdate(index, action.copy(delayMs = if (value) millisecondsFromSeconds(seconds) else 0))
                            })
                            if (delayed) FormField("等待时间（秒）", seconds, { value ->
                                seconds = value; onUpdate(index, action.copy(delayMs = millisecondsFromSeconds(value)))
                            }, singleLine = true, supporting = "支持小数，例如 0.5 秒；最长 24 小时。")
                            ToggleRow("包括常驻通知", action.includeOngoing, { onUpdate(index, action.copy(includeOngoing = it)) })
                        }
                        is WebhookAction -> {
                            ChoiceMenu("发送到", action.profileId, profiles.map { it.id to (it.name + if (it.configCipher.isEmpty()) " · 待补填" else "") }, {
                                onUpdate(index, action.copy(profileId = it))
                            })
                            if (profiles.none { it.id == action.profileId }) Text(if (profiles.isEmpty()) "请先在“转发”中创建 Telegram 或 Webhook 配置。" else "原配置已不存在，请重新选择。",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (openProfile != null) {
                                val hasProfile = profiles.any { it.id == action.profileId }
                                TextButton(onClick = { openProfile(if (hasProfile) action.profileId else "new") }) {
                                    Text(if (hasProfile) "编辑转发设置" else "新建转发配置")
                                }
                            }
                            ToggleRow("相同内容不重复发送", action.dedupEnabled, { onUpdate(index, action.copy(dedupEnabled = it)) })
                            var window by rememberSaveable(ruleId, revision, index, "window") { mutableStateOf(action.dedupWindowSeconds.toString()) }
                            FormField("去重时间（秒）", window, { value ->
                                window = value; onUpdate(index, action.copy(dedupWindowSeconds = value.toIntOrNull() ?: -1))
                            }, singleLine = true, supporting = if (action.dedupEnabled) "这段时间内，相同通知只发送一次。" else "重新开启去重时使用此时间。")
                        }
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AddTag("消除通知", { onAdd(DismissAction()) })
        AddTag("转发消息", { onAdd(WebhookAction(profiles.firstOrNull()?.id.orEmpty())) })
    }
}

fun secondsLabel(milliseconds: Long): String = BigDecimal.valueOf(milliseconds).movePointLeft(3).stripTrailingZeros().toPlainString()
fun millisecondsFromSeconds(value: String): Long = runCatching { value.toBigDecimal().movePointRight(3).longValueExact() }.getOrDefault(-1L)

@Composable
fun RuleOverview(rule: Rule, choices: List<AppChoice>, profileNames: Map<String, String>) {
    val names = remember(choices) { choices.associate { it.packageName to it.name } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rule.applications.isEmpty()) SummaryTag("未选择应用")
        rule.applications.take(4).forEach { target ->
            SummaryTag(targetCaption(target, names[target.packageName] ?: target.packageName))
        }
        if (rule.applications.size > 4) SummaryTag("另 ${rule.applications.size - 4} 个")
    }
    val condition = rule.textCondition
    Text(if (condition.values.isEmpty()) "所有通知内容" else operatorLabel(condition.operator) + "：" + condition.values.joinToString("、"),
        color = NotifyColors.inkSoft, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    Text(rule.actions.joinToString(" · ") { action -> when (action) {
        is DismissAction -> if (action.delayMs == 0L) "立即消除" else secondsLabel(action.delayMs) + " 秒后消除"
        is WebhookAction -> "转发到 " + (profileNames[action.profileId] ?: "待确认配置")
    } }, color = NotifyColors.blue, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SummaryTag(label: String) {
    Surface(color = NotifyColors.blueTint, shape = RoundedCornerShape(16.dp)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = NotifyColors.inkSoft,
            style = MaterialTheme.typography.labelMedium, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
