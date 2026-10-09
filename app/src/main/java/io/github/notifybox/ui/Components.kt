package io.github.notifybox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.notifybox.R
import io.github.notifybox.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PageIntro(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = NotifyColors.ink)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NotifyColors.muted)
    }
}
@Composable
fun SectionCard(title: String, enabledState: Boolean? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        shape = RoundedCornerShape(if (enabledState == null) 20.dp else 22.dp),
        border = BorderStroke(1.dp, NotifyColors.line),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = if (enabledState == false) NotifyColors.disabled else Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (enabledState != null) Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (enabledState) NotifyColors.blueTint else NotifyColors.muted.copy(alpha = .35f)))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = NotifyColors.ink)
            }
            content()
        }
    }
}
@Composable
fun PageControls(page: Int, count: Int, onPage: (Int) -> Unit) {
    val pages = ((count.coerceAtLeast(0) + 19) / 20).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = page > 0, onClick = { onPage(page - 1) }) { Text("上一页") }
        Text(if (count < 0) "加载中" else "${page + 1} / $pages 页 · $count 条", style = MaterialTheme.typography.labelMedium)
        TextButton(enabled = count >= 0 && page + 1 < pages, onClick = { onPage(page + 1) }) { Text("下一页") }
    }
}
@Composable
fun EmptyCard(title: String, text: String) {
    SectionCard(title) { Text(text, color = NotifyColors.muted) }
}
@Composable
fun PrimaryAction(label: String, onClick: () -> Unit, showAddIcon: Boolean = false) {
    Button(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NotifyColors.blue, contentColor = Color.White)) {
        if (showAddIcon) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.Bold)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge, color = NotifyColors.ink) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(NotifyColors.blueTint), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "返回", tint = NotifyColors.blue, modifier = Modifier.size(19.dp))
                }
            }
        }, actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = NotifyColors.background))
}
@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = NotifyColors.blue,
            uncheckedThumbColor = NotifyColors.muted, uncheckedTrackColor = NotifyColors.disabled,
            uncheckedBorderColor = NotifyColors.line))
    }
}
@Composable
fun FormField(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = false, supporting: String? = null, maxLines: Int = Int.MAX_VALUE) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), singleLine = singleLine, maxLines = if (singleLine) 1 else maxLines,
        supportingText = if (supporting == null) null else { { Text(supporting) } },
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedBorderColor = NotifyColors.blue, unfocusedBorderColor = NotifyColors.line,
            focusedLabelColor = NotifyColors.blue, unfocusedLabelColor = NotifyColors.muted))
}
fun timeLabel(millis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))
fun userLabel(id: Int?) = when (id) { null -> "所有用户"; 0 -> "主应用 · 0"; 999 -> "用户 999 · 分身待验证"; else -> "用户 " + id }
fun statusLabel(status: String): String = when (status) {
    "PENDING" -> "待处理"; "SENDING" -> "发送中"; "SUCCESS" -> "成功"; "FAILED" -> "失败"; "UNKNOWN" -> "结果未知"
    "DEDUP" -> "已去重"; "SKIPPED" -> "已跳过"; "CANCELLED" -> "已取消"; "STALE" -> "内容已更新"
    "REQUESTED" -> "已请求消除"; "REMOVED" -> "已移除"; "MISSING" -> "已不在列表"; else -> status
}
fun eventLabel(kind: String) = when (kind) { "ARRIVED" -> "到达"; "UPDATED" -> "更新"; else -> "移除" }
fun ruleSummary(rule: Rule): String {
    val apps = if (rule.applications.isEmpty()) "未选择应用，不处理任何通知" else rule.applications.joinToString("、") { it.packageName + " (" + userLabel(it.userId) + ")" }
    val text = if (rule.textCondition.values.isEmpty()) "不限文本" else operatorLabel(rule.textCondition.operator) + " " + rule.textCondition.values.joinToString(" / ")
    return apps + "\n" + text
}
fun operatorLabel(op: TextOperator) = when (op) {
    TextOperator.CONTAINS -> "包含"; TextOperator.NOT_CONTAINS -> "不包含任一"; TextOperator.ANY -> "包含任一"
    TextOperator.ALL -> "包含全部"; TextOperator.REGEX -> "正则任一"
}
fun actionSummary(rule: Rule) = rule.actions.joinToString(" · ") {
    when (it) {
        is DismissAction -> { if (it.delayMs == 0L) "立即消除" else secondsLabel(it.delayMs) + " 秒后消除" }
        is WebhookAction -> "Webhook 转发"
    }
}
