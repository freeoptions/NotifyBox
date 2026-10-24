package io.github.notifybox.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
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
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.core.ApplicationTarget
import io.github.notifybox.core.NotificationSnapshot
import io.github.notifybox.core.RuleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

@Composable
fun rememberApplicationChoices(vm: AppViewModel, targets: List<ApplicationTarget> = emptyList()): State<List<AppChoice>> {
    val installed by vm.installedApps.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.loadApplications() }
    return produceState(installed, installed, records, targets) {
        value = withContext(Dispatchers.Default) {
            val observed = records.mapNotNull { row ->
                runCatching { RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson) }.getOrNull()
            }.map { AppChoice(it.packageName, it.appName, it.userId, AppChoiceSource.NOTIFICATION) }
            mergeApplicationChoices(installed, observed, targets)
        }
    }
}

object ApplicationCatalog {
    // 列表只加载名称，图标按可见项异步加载。最多缓存 128 个 96px 图标。
    private val icons = LruCache<String, ImageBitmap>(128)

    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppChoice> {
        val pm = context.packageManager
        val currentUserId = androidUserIdFromUid(Process.myUid())
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).mapTo(mutableListOf()) { entry ->
            AppChoice(entry.activityInfo.packageName, entry.loadLabel(pm).toString(), currentUserId,
                AppChoiceSource.INSTALLED)
        }
        val launcher = context.getSystemService(LauncherApps::class.java)
        // 仅查询系统公开可访问的用户，不猜测 999、不使用跨用户隐藏 API。
        val users = runCatching { launcher.profiles }.getOrDefault(emptyList())
        users.forEach { user ->
            val kind = if (Build.VERSION.SDK_INT >= 35) runCatching {
                when (launcher.getLauncherUserInfo(user)?.userType) {
                    UserManager.USER_TYPE_PROFILE_CLONE -> AppProfileKind.CLONE
                    UserManager.USER_TYPE_PROFILE_MANAGED -> AppProfileKind.WORK
                    UserManager.USER_TYPE_PROFILE_PRIVATE -> AppProfileKind.PRIVATE
                    else -> AppProfileKind.UNKNOWN
                }
            }.getOrDefault(AppProfileKind.UNKNOWN) else AppProfileKind.UNKNOWN
            val activities = runCatching { launcher.getActivityList(null, user) }.getOrDefault(emptyList())
            activities.forEach activity@{ entry ->
                val uid = entry.applicationInfo.uid
                val userId = androidUserIdFromUid(uid) ?: return@activity
                // 厂商若返回了主用户的 ApplicationInfo，不把它误标成另一用户的实例。
                if (UserHandle.getUserHandleForUid(uid) != user) return@activity
                apps.add(AppChoice(entry.componentName.packageName, entry.label.toString(), userId,
                    AppChoiceSource.INSTALLED, kind))
            }
        }
        return apps.asReversed().filter { it.packageName != context.packageName }
            .distinctBy { it.target }.sortedBy { it.name.lowercase() }
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
fun ApplicationIcon(packageName: String, name: String, size: Dp = 28.dp) {
    val context = LocalContext.current.applicationContext
    val preview = LocalInspectionMode.current
    val bitmap by produceState<ImageBitmap?>(null, packageName, preview) {
        if (!preview) value = withContext(Dispatchers.IO) { ApplicationCatalog.icon(context, packageName) }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
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
    var scope by remember { mutableStateOf("instances") }
    var user by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<ApplicationTarget>()) }
    val userId = when (scope) { "all" -> null; "main" -> 0; else -> user.toIntOrNull() }
    val validUser = scope != "custom" || (userId != null && userId >= 0)
    val candidates = remember(choices, scope, userId) { applicationChoicesForScope(choices, scope, userId) }
    val filtered by key(candidates, query) {
        produceState<List<AppChoice>>(emptyList(), candidates, query) {
            value = withContext(Dispatchers.Default) {
                candidates.filter { SearchIndex.matches(it.name, query) || it.packageName.contains(query.trim(), true) }
            }
        }
    }
    val selectedTargets = selected.intersect(candidates.mapTo(mutableSetOf()) { it.target })
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择应用") },
        text = {
            // 表单与应用列表共享一个滚动容器，键盘和大字号下仍能看到确认按钮。
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FormField("应用名称、拼音或包名", query, { query = it }, singleLine = true) }
                item { ChoiceMenu("通知来自", scope, listOf("instances" to "自动识别应用实例", "all" to "所有用户", "main" to "主应用", "custom" to "指定用户 / 分身"), {
                    scope = it; selected = emptySet()
                }) }
                if (scope == "custom") item {
                    val detected = choices.filter { it.verified && it.userId != 0 }.mapNotNull { it.userId }.distinct().sorted()
                    FormField("用户标识", user, { user = it; selected = emptySet() }, singleLine = true,
                        supporting = if (detected.isEmpty()) "填写非负整数；列表只显示已识别的该用户应用。" else "已识别用户：${detected.joinToString("、")}。")
                }
                item { Text("系统未公开的分身，可在它收到通知后识别。通知记录只能证明曾出现过该实例。",
                    color = NotifyColors.muted, style = MaterialTheme.typography.bodySmall) }
                if (!loaded) item { Text("正在读取应用…", Modifier.padding(12.dp)) }
                if (loaded && filtered.isEmpty()) item { Text(if (!validUser) "请填写有效的用户标识。" else if (scope == "custom") "未识别到该用户的匹配应用。可让分身收到一条通知后再选择，或手动输入包名。" else "没有找到应用，可手动添加包名。", Modifier.padding(12.dp)) }
                items(filtered, key = { it.packageName + "|" + (it.userId?.toString() ?: "*") }) { app ->
                    Row(Modifier.fillMaxWidth().toggleable(value = app.target in selectedTargets, role = Role.Checkbox, onValueChange = { checked ->
                        selected = if (checked) selected + app.target else selected - app.target
                    }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ApplicationIcon(app.packageName, app.name)
                        Column(Modifier.weight(1f)) {
                            Text(applicationChoiceLabel(app), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = NotifyColors.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (app.source == AppChoiceSource.NOTIFICATION) Text("来自通知记录",
                                style = MaterialTheme.typography.bodySmall, color = NotifyColors.muted)
                        }
                        Checkbox(checked = app.target in selectedTargets, onCheckedChange = null)
                    }
                }
                item { TextButton(onClick = onManual) { Text("手动输入包名") } }
            }
        },
        confirmButton = { TextButton(enabled = selectedTargets.isNotEmpty() && validUser, onClick = {
            onAdd(selectedTargets.toList())
        }) { Text("添加 (${selectedTargets.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
