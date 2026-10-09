package io.github.notifybox.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.notifybox.R
import io.github.notifybox.listener.BackgroundService

object DeliveryAlerts {
    // 所有网络问题共用一条提醒；反复重试只更新，不反复响铃。
    fun failed(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("delivery_errors", "转发失败提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, "delivery_errors").setSmallIcon(R.drawable.ic_notifybox)
            .setContentTitle("NotifyBox 转发未成功")
            .setContentText("请检查代理节点与转发配置；打开转发页查看待处理任务")
            .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(BackgroundService.openApp(context)).build()
        try { NotificationManagerCompat.from(context).notify(1002, notification) }
        catch (_: SecurityException) { /* 设置页显示通知权限状态。 */ }
    }
}
