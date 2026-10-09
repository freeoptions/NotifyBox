package io.github.notifybox.listener

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.notifybox.appGraph
import io.github.notifybox.core.NotificationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ListenerState {
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    val dropped = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    fun connection(value: Boolean) { mutableConnected.value = value }
}
class NotificationCollector : NotificationListenerService() {
    private val appNames = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }
    override fun onListenerConnected() {
        ListenerState.connection(true)
        appGraph.processor.connect(this)
    }
    override fun onListenerDisconnected() {
        ListenerState.connection(false)
        appGraph.processor.disconnect(this)
        BackgroundService.requestListener(this)
    }
    override fun onDestroy() {
        ListenerState.connection(false)
        appGraph.processor.disconnect(this)
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        appGraph.processor.posted(snapshot(sbn))
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        appGraph.processor.removed(snapshot(sbn))
    }
    fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val name = appNames.getOrPut(sbn.packageName) {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }.orEmpty()
        // UserHandle.identifier 未向普通应用公开；通过通知的公开 API 获取所属用户 ID。
        @Suppress("DEPRECATION")
        val userId = sbn.userId
        return NotificationSnapshot(sbn.key, sbn.packageName, userId, name, title, text,
            sbn.postTime, System.currentTimeMillis(), sbn.isOngoing, sbn.groupKey,
            (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
    }
}
