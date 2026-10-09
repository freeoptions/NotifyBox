package io.github.notifybox.ui

import android.content.Context
import android.content.Intent
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.core.ApplicationTarget
import io.github.notifybox.core.NotificationSnapshot
import io.github.notifybox.core.RuleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

data class AppChoice(val packageName: String, val name: String)

@Composable
fun rememberApplicationChoices(vm: AppViewModel, targets: List<ApplicationTarget> = emptyList()): State<List<AppChoice>> {
    val installed by vm.installedApps.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.loadApplications() }
    return produceState(installed, installed, records, targets) {
        value = withContext(Dispatchers.Default) {
            val observed = records.mapNotNull { row ->
                runCatching { RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson) }.getOrNull()
            }.map { AppChoice(it.packageName, it.appName) }
            (installed + observed + targets.map { AppChoice(it.packageName, it.packageName) })
                .distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
        }
    }
}

object ApplicationCatalog {
    // 列表只加载名称，图标按可见项异步加载。最多缓存 128 个 96px 图标。
    private val icons = LruCache<String, ImageBitmap>(128)

    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppChoice> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { entry ->
            AppChoice(entry.activityInfo.packageName, entry.loadLabel(pm).toString())
        }.distinctBy { it.packageName }.filter { it.packageName != context.packageName }
            .sortedBy { it.name.lowercase() }
    }

    fun icon(context: Context, packageName: String): ImageBitmap? {
        synchronized(icons) { icons.get(packageName) }?.let { return it }
        val bitmap = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val edge = maxOf(width, height)
            drawable.toBitmap((96L * width / edge).toInt().coerceAtLeast(1),
                (96L * height / edge).toInt().coerceAtLeast(1)).asImageBitmap()
        }.getOrNull()
        if (bitmap != null) synchronized(icons) { icons.put(packageName, bitmap) }
        return bitmap
    }
}

@Composable
fun ApplicationIcon(packageName: String, name: String) {
    val context = LocalContext.current.applicationContext
    val preview = LocalInspectionMode.current
    val bitmap by produceState<ImageBitmap?>(null, packageName, preview) {
        if (!preview) value = withContext(Dispatchers.IO) { ApplicationCatalog.icon(context, packageName) }
    }
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Surface(color = NotifyColors.disabled, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { Text(name.take(1), color = NotifyColors.blue, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun ApplicationPicker(choices: List<AppChoice>, loaded: Boolean, onDismiss: () -> Unit, onAdd: (List<ApplicationTarget>) -> Unit, onManual: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("all") }
    var user by remember { mutableStateOf("999") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val filtered by produceState(choices, choices, query) {
        value = withContext(Dispatchers.Default) {
            choices.filter { SearchIndex.matches(it.name, query) || it.packageName.contains(query.trim(), true) }
        }
    }
    val userId = when (scope) { "all" -> null; "main" -> 0; else -> user.toIntOrNull() }
    val validUser = scope != "custom" || (userId != null && userId >= 0)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择应用") },
        text = {
            // 表单与应用列表共享一个滚动容器，键盘和大字号下仍能看到确认按钮。
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FormField("应用名称、拼音或包名", query, { query = it }, singleLine = true) }
                item { ChoiceMenu("通知来自", scope, listOf("all" to "所有用户", "main" to "主应用", "custom" to "指定用户 / 分身"), { scope = it }) }
                if (scope == "custom") item {
                    FormField("用户标识", user, { user = it }, singleLine = true, supporting = "小米分身常用 999；填写非负整数。")
                }
                if (!loaded && choices.isEmpty()) item { Text("正在读取应用…", Modifier.padding(12.dp)) }
                if (loaded && filtered.isEmpty()) item { Text("没有找到应用，可手动添加包名。", Modifier.padding(12.dp)) }
                items(filtered, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().toggleable(value = app.packageName in selected, role = Role.Checkbox, onValueChange = { checked ->
                        selected = if (checked) selected + app.packageName else selected - app.packageName
                    }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ApplicationIcon(app.packageName, app.name)
                        Column(Modifier.weight(1f)) {
                            Text(app.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = NotifyColors.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                    }
                }
                item { TextButton(onClick = onManual) { Text("手动输入包名") } }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty() && validUser, onClick = {
            onAdd(selected.map { ApplicationTarget(it, userId) })
        }) { Text("添加 (${selected.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

fun targetCaption(target: ApplicationTarget, name: String): String = when (target.userId) {
    null -> name
    0 -> "$name · 主应用"
    999 -> "$name · 分身 999"
    else -> "$name · 用户 ${target.userId}"
}

fun parseApplicationTargets(source: String): List<ApplicationTarget> = source.lineSequence().filter { it.isNotBlank() }.map { line ->
    val parts = line.trim().split('|')
    require(parts.size in 1..2 && parts[0].isNotBlank()) { "应用格式：包名|用户标识，或包名|*" }
    val user = parts.getOrNull(1)?.trim()
    val id = if (user == null || user == "*") null else user.toInt()
    require(id == null || id >= 0) { "用户标识必须是非负整数或 *" }
    ApplicationTarget(parts[0].trim(), id)
}.toList()

fun formatApplicationTargets(targets: List<ApplicationTarget>): String = targets.joinToString("\n") { it.packageName + "|" + (it.userId?.toString() ?: "*") }
