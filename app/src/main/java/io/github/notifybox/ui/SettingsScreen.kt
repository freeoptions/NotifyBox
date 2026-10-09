package io.github.notifybox.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.core.*
import io.github.notifybox.listener.ListenerState
import io.github.notifybox.listener.BackgroundService
import io.github.notifybox.data.ExportStorage

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)) }
    var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryAllowed by remember { mutableStateOf((context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)) }
    var backgroundAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 28 || !(context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted) }
    var exportTree by remember { mutableStateOf(vm.repository.exportTree()) }
    var exportAvailable by remember { mutableStateOf(ExportStorage.available(context, exportTree)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
                notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                batteryAllowed = (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
                backgroundAllowed = Build.VERSION.SDK_INT < 28 || !(context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
                exportAvailable = ExportStorage.available(context, exportTree)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val connected by ListenerState.connected.collectAsStateWithLifecycle()
    val running by BackgroundService.running.collectAsStateWithLifecycle()
    val listenerError by ListenerState.error.collectAsStateWithLifecycle()
    val dropped by ListenerState.dropped.collectAsStateWithLifecycle()
    var days by rememberSaveable { mutableStateOf(vm.repository.retentionDays().toString()) }
    var preview by remember { mutableStateOf<ImportReport?>(null) }
    var clear by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var retryContinuously by remember { mutableStateOf(vm.repository.continuousRetry()) }
    var proxy by remember { mutableStateOf(vm.repository.proxyAddress()) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.launch {
            val source = context.contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024) { "导入文件不能超过 2 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            } ?: error("无法读取文件")
            try { preview = ImportAdapter.preview(source) } catch (_: Exception) { throw IllegalArgumentException("导入失败：JSON 或规则结构无效。原始内容未保存。") }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.launch("导出位置已保存") {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.repository.setExportTree(uri.toString())
            exportTree = uri.toString()
            exportAvailable = ExportStorage.available(context, exportTree)
        }
    }
    fun openSettings(intent: Intent) {
        try { context.startActivity(intent) } catch (_: Exception) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
        }
    }
    Column(Modifier.fillMaxSize().background(NotifyColors.background).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageIntro("设置", "")
        ThemeSettings(vm)
        SectionCard("权限与后台运行") {
            PermissionItem("通知访问", granted) { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            PermissionItem("通知提醒", notificationsAllowed) {
                if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            PermissionItem("忽略电池优化", batteryAllowed) {
                openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
            }
            PermissionItem("允许系统后台运行", backgroundAllowed) {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            }
            ManualPermission(vm, "autostart", "允许自启动") {
                openSettings(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            }
            ManualPermission(vm, "battery", "厂商电池策略设为不限制") {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            }
            ManualPermission(vm, "recent", "最近任务锁定")
            Text("监听：" + (if (connected) "已连接" else "未连接") + " · 后台服务：" + if (running) "运行中" else "未运行")
            TextButton(onClick = {
                BackgroundService.start(context)
                if (!connected) BackgroundService.requestListener(context)
            }) { Text("恢复后台监听") }
            listenerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (dropped > 0) Text("本次运行未采集事件：" + dropped, color = MaterialTheme.colorScheme.error)
            Text("带“手动”标记的项目仅记录您的确认，系统未提供可靠检测接口。", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("转发网络与重试") {
            FormField("代理地址（留空使用系统网络 / VPN）", proxy, { proxy = it }, singleLine = true,
                supporting = "http://127.0.0.1:端口 或 socks://127.0.0.1:端口")
            TextButton(onClick = { vm.launch("代理已保存，下次发送时生效") { vm.repository.setProxyAddress(proxy.trim()) } }) { Text("保存代理") }
            Text("使用 VPN 代理时，请在代理软件中允许 NotifyBox 经过代理。", style = MaterialTheme.typography.bodySmall)
            ToggleRow("网络故障持续重试", retryContinuously, {
                retryContinuously = it; vm.repository.setContinuousRetry(it)
            })
            Text("失败时通知提醒；重试间隔逐步增加，最长 30 分钟，服务端要求更久时按其等待。超时后重发可能重复接收，凭证错误需修正后手动重试。", style = MaterialTheme.typography.bodySmall)
            if (!retryContinuously) Text("关闭后，明确未发送的故障最多尝试 3 次；接收结果未知时需手动重试。", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("本地数据") {
            FormField("保留天数（1—90）", days, { days = it }, singleLine = true)
            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), onClick = {
                val value = days.toIntOrNull()
                vm.launch("保留时间已更新") {
                    require(value != null && value in 1..90) { "请输入 1 到 90 天" }
                    vm.repository.setRetentionDays(value)
                    vm.repository.prune()
                }
            }) { Text("保存保留时间") }
            Text("通知与已完成任务按时间保留，最多各 10000 条。待发送任务不因到期删除，上限 1000 条。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clear = true }) { Text("清空通知与动作记录") }
        }
        SectionCard("规则导入与导出") {
            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("导入 JSON") }
            Text("导出位置：" + ExportStorage.label(context, exportTree))
            if (exportTree != null && !exportAvailable) Text("目录权限已失效，请重新选择", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { exportLauncher.launch(exportTree?.let(Uri::parse)) }) { Text("选择导出文件夹") }
            OutlinedButton(enabled = exportAvailable, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), onClick = {
                vm.launch("配置已导出到指定位置") { ExportStorage.export(context, vm.repository) }
            }) { Text("导出完整配置") }
        }
        Spacer(Modifier.height(12.dp))
    }
    preview?.let { report ->
        AlertDialog(onDismissRequest = { if (!importBusy) preview = null }, title = { Text("导入预览 · " + report.items.size + " 条规则") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    report.notes.forEach { Text(it) }
                    report.items.forEach { item ->
                        Text(item.rule.name, style = MaterialTheme.typography.titleSmall)
                        Text(ruleSummary(item.rule) + "\n" + actionSummary(item.rule))
                        item.notes.forEach { Text("说明：" + it, style = MaterialTheme.typography.bodySmall) }
                        item.blockers.forEach { Text("待确认：" + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(enabled = !importBusy && report.items.isNotEmpty(), onClick = {
                importBusy = true
                vm.launch("规则已新增，默认关闭", after = { preview = null; importBusy = false }) {
                    try { vm.repository.import(report) } catch (e: Exception) { importBusy = false; throw e }
                }
            }) { Text(if (importBusy) "保存中" else "确认新增") } },
            dismissButton = { TextButton(enabled = !importBusy, onClick = { preview = null }) { Text("取消") } })
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空通知与动作记录？") },
        text = { Text("规则和转发队列保留，不撤回已经发送的内容。") },
        confirmButton = { TextButton(onClick = { clear = false; vm.launch("记录已清空") { vm.repository.clearRecords() } }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}

@Composable
private fun PermissionItem(label: String, checked: Boolean, manage: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text((if (checked) "✅ " else "⬜ ") + label, Modifier.weight(1f))
        TextButton(onClick = manage) { Text("设置") }
    }
}

@Composable
private fun ManualPermission(vm: AppViewModel, key: String, label: String, manage: (() -> Unit)? = null) {
    var checked by remember(key) { mutableStateOf(vm.repository.manuallyChecked(key)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, { checked = it; vm.repository.setManuallyChecked(key, it) })
        Text(label + "（手动）", Modifier.weight(1f))
        if (manage != null) TextButton(onClick = manage) { Text("设置") }
    }
}
