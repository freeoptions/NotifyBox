package io.github.notifybox.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.notifybox.R
import io.github.notifybox.core.DeliveryPolicy
import io.github.notifybox.core.TaskStatus
import io.github.notifybox.listener.BackgroundService

object DeliveryAlerts {
    // 所有网络问题共用一条提醒；反复重试只更新，不反复响铃。
    fun failed(context: Context, attempts: Int, status: TaskStatus) {
        if (!DeliveryPolicy.shouldAlert(attempts, status)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("delivery_errors", "转发失败提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, "delivery_errors").setSmallIcon(R.drawable.ic_notifybox)
            .setContentTitle("NotifyBox 已尝试 3 次，转发仍未成功")
            .setContentText("请检查或切换代理节点；打开转发页查看任务并立即重试")
            .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(BackgroundService.openApp(context)).build()
        try { NotificationManagerCompat.from(context).notify(1002, notification) }
        catch (_: SecurityException) { /* 通知提醒是可选权限；发送结果仍保留在转发记录中。 */ }
    }
}
