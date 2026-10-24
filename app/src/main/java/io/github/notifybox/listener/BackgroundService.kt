package io.github.notifybox.listener

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.notifybox.MainActivity
import io.github.notifybox.R
import io.github.notifybox.appGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/** 独立于 Activity 生命周期；网络仍由 WorkManager 执行。 */
class BackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("background", "后台通知管理", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, "background")
            .setSmallIcon(R.drawable.ic_notifybox).setContentTitle("NotifyBox 正在后台运行")
            .setContentText("正在连接通知监听").setOngoing(true).setSilent(true)
            .setContentIntent(openApp(this)).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1001, notification)
        running.value = true
        scope.launch {
            ListenerState.connected.collect { connected ->
                manager.notify(1001, NotificationCompat.Builder(this@BackgroundService, "background")
                    .setSmallIcon(R.drawable.ic_notifybox).setContentTitle("NotifyBox 正在后台运行")
                    .setContentText(if (connected) "通知监听已连接" else "通知监听断开，正在重新连接")
                    .setOngoing(true).setSilent(true).setContentIntent(openApp(this@BackgroundService)).build())
            }
        }
        scope.launch {
            // 断连后的前一分钟主动恢复，短时唤醒防止息屏把重试一起暂停。
            // 连接成功立即释放；超时后恢复为低频检查，不长期占用唤醒锁。
            ListenerState.connected.collectLatest { connected ->
                if (connected) return@collectLatest
                val recoveryWake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotifyBox:listener-recovery")
                    .apply { setReferenceCounted(false) }
                recoveryWake.acquire(60_000)
                try {
                    for (waitMs in listOf(0L, 1000L, 5000L, 15000L, 30000L)) {
                        delay(waitMs)
                        if (packageName !in NotificationManagerCompat.getEnabledListenerPackages(this@BackgroundService)) break
                        requestListener(this@BackgroundService)
                    }
                } finally { if (recoveryWake.isHeld) recoveryWake.release() }
            }
        }
        scope.launch {
            while (isActive) {
                if (packageName !in NotificationManagerCompat.getEnabledListenerPackages(this@BackgroundService)) {
                    stopSelf(); break
                }
                // 不只相信内存中的“已连接”；通过串行处理器核对实际监听并补收遗漏。
                appGraph.processor.checkListenerHealth()
                delay(30_000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!ListenerState.connected.value) requestListener(this)
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running.value = false; scope.cancel()
        super.onDestroy()
    }
    companion object {
        val running = MutableStateFlow(false)
        fun start(context: Context) {
            if (running.value) return
            if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) return
            try { ContextCompat.startForegroundService(context, Intent(context, BackgroundService::class.java)) }
            catch (_: RuntimeException) { ListenerState.error.value = "后台服务未能启动，请打开应用并检查电池限制" }
        }
        fun requestListener(context: Context) {
            if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) return
            try { NotificationListenerService.requestRebind(ComponentName(context, NotificationCollector::class.java)) }
            catch (_: RuntimeException) { ListenerState.error.value = "系统未接受通知监听重连，请检查通知访问与后台限制" }
        }
        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            BackgroundService.start(context)
            BackgroundService.requestListener(context)
        }
    }
}
