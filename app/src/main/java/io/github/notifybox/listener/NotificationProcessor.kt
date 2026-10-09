package io.github.notifybox.listener

import android.content.Context
import android.os.SystemClock
import android.os.PowerManager
import androidx.room.withTransaction
import io.github.notifybox.core.*
import io.github.notifybox.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class NotificationProcessor(
    private val context: Context, private val repository: Repository,
    private val scope: CoroutineScope, private val ready: Deferred<Unit>,
) {
    private sealed interface Command {
        data class Posted(val n: NotificationSnapshot) : Command
        data class Removed(val n: NotificationSnapshot) : Command
        data class Connected(val service: NotificationCollector) : Command
        data class Disconnected(val service: NotificationCollector) : Command
        data object Tick : Command
    }
    private val channel = Channel<Command>(512)
    private val planner = DismissPlanner()
    private val engine = RuleEngine()
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotifyBox:notification-processing").apply { setReferenceCounted(false) }
    private var service: NotificationCollector? = null
    private data class Origin(val recordId: String, val ruleName: String)
    private val origins = mutableMapOf<String, MutableMap<String, Origin>>()
    private val awaitingRemoval = mutableMapOf<String, Pair<Long, List<Origin>>>()
    init {
        scope.launch {
            try { ready.await() } catch (_: Exception) { ListenerState.error.value = "数据初始化失败，请检查存储空间"; return@launch }
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val nextDeadline = listOfNotNull(if (service == null) null else planner.nextDueAt(), awaitingRemoval.values.minOfOrNull { it.first + 5000 }).minOrNull()
                if (nextDeadline != null && nextDeadline - now <= 60_000) wakeLock.acquire((nextDeadline - now + 5000).coerceIn(5000, 65_000))
                else if (wakeLock.isHeld) wakeLock.release()
                val command = when {
                    nextDeadline == null -> channel.receive()
                    nextDeadline <= now -> Command.Tick
                    else -> withTimeoutOrNull(nextDeadline - now) { channel.receive() } ?: Command.Tick
                }
                wakeLock.acquire(60_000)
                try {
                    when (command) {
                        is Command.Connected -> {
                            service = command.service
                            restore(command.service)
                        }
                        is Command.Disconnected -> if (service === command.service) {
                            service = null
                        }
                        is Command.Posted -> onPosted(command.n)
                        is Command.Removed -> onRemoved(command.n)
                        Command.Tick -> {
                            dismissDue()
                            val expired = awaitingRemoval.filterValues { SystemClock.elapsedRealtime() - it.first >= 5000 }.keys.toList()
                            expired.forEach { key ->
                                awaitingRemoval.remove(key)?.second?.forEach { origin ->
                                    repository.action(origin.recordId, origin.ruleName, "消除", "UNKNOWN", "已请求系统消除，但未收到移除回调")
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (command is Command.Connected) { service = null; ListenerState.connection(false) }
                    ListenerState.error.value = "有一条通知处理失败，请检查存储或配置"
                    delay(1000)
                }
            }
        }
    }
    private fun send(command: Command) {
        if (command is Command.Posted || command is Command.Connected) wakeLock.acquire(60_000)
        if (channel.trySend(command).isFailure) {
            ListenerState.dropped.value += 1
            ListenerState.error.value = "通知过于密集，处理队列已满；部分事件未采集"
        }
    }
    fun connect(value: NotificationCollector) = send(Command.Connected(value))
    fun disconnect(value: NotificationCollector) = send(Command.Disconnected(value))
    fun posted(n: NotificationSnapshot) = send(Command.Posted(n))
    fun removed(n: NotificationSnapshot) = send(Command.Removed(n))
    private suspend fun invalidate(key: String, reason: String) {
        repository.dao.deleteDismissPlans(key)
        origins.remove(key)?.values?.forEach { repository.action(it.recordId, it.ruleName, "消除", "CANCELLED", reason) }
        awaitingRemoval.remove(key)?.second?.forEach { repository.action(it.recordId, it.ruleName, "消除", "UNKNOWN", "此前已请求消除；" + reason) }
    }
    private suspend fun restore(listener: NotificationCollector) {
        // 重连先核对系统活跃内容，再恢复持久化的原始截止时间；不重复转发旧通知。
        val current = withContext(Dispatchers.Main.immediate) {
            listener.activeNotifications.filter { it.packageName != context.packageName }.map { listener.snapshot(it) }
        }.associateBy { it.key }
        planner.clear(); origins.clear()
        repository.dao.dismissPlans().groupBy { it.notificationKey }.forEach { (key, plans) ->
            val n = current[key]
            val saved = RuleJson.decodeFromString<NotificationSnapshot>(plans.first().snapshotJson)
            if (n == null || !saved.sameContent(n)) {
                plans.forEach { repository.action(it.recordId, it.ruleName, "消除", "CANCELLED", "通知已移除或内容变化，旧延迟任务取消") }
                repository.dao.deleteDismissPlans(key)
            } else {
                planner.restore(saved, plans.first().revision)
                plans.forEach { plan ->
                    planner.schedule(key, plan.revision, SystemClock.elapsedRealtime() + (plan.dueAt - System.currentTimeMillis()).coerceAtLeast(0), plan.ruleId)
                    origins.getOrPut(key) { mutableMapOf() }[plan.ruleId] = Origin(plan.recordId, plan.ruleName)
                }
            }
        }
        current.values.forEach { n ->
            if (planner.current(n.key) == null) {
                val last = repository.dao.latestNotification(n.key)
                if (last != null && last.kind != EventKind.REMOVED.name && !repository.dao.notificationWasRemoved(last.id) &&
                    RuleJson.decodeFromString<NotificationSnapshot>(last.snapshotJson).sameContent(n)) planner.restore(n, last.revision)
                else onPosted(n, recovered = true)
            }
        }
        dismissDue()
    }
    private suspend fun onPosted(n: NotificationSnapshot, recovered: Boolean = false) {
        if (n.packageName == context.packageName) return
        val update = planner.posted(n)
        if (!update.changed) return
        invalidate(n.key, "通知内容已更新，旧消除任务失效")
        val matched = repository.allRules().filter { engine.matches(it, n) }
        val recordId = newId()
        try {
            repository.db.withTransaction {
                repository.dao.addNotification(repository.indexed(NotificationRow(recordId, n.key, update.active.revision,
                    if (update.arrived) EventKind.ARRIVED.name else EventKind.UPDATED.name,
                    RuleJson.encodeToString(n), matched.joinToString("、") { it.name }, n.receivedAt), n))
                // 先持久化所有转发任务，再调度消除；网络执行完全独立。
                matched.forEach { rule -> rule.actions.filterIsInstance<WebhookAction>().forEach { action ->
                    try { repository.enqueue(recordId, rule, action, n) } catch (_: Exception) { repository.action(recordId, rule.name, "转发", "FAILED", "配置、模板或本地加密失败，请检查转发配置") }
                } }
                matched.forEach { rule -> rule.actions.filterIsInstance<DismissAction>().forEach { action ->
                    if (n.ongoing && !action.includeOngoing) repository.action(recordId, rule.name, "消除", "SKIPPED", "常驻通知未允许处理")
                    else {
                        val startedAt = if (recovered) n.postedAt else n.receivedAt
                        val remaining = (action.delayMs - (System.currentTimeMillis() - startedAt).coerceAtLeast(0)).coerceAtLeast(0)
                        planner.schedule(n.key, update.active.revision, SystemClock.elapsedRealtime() + remaining, rule.id)
                        val currentOrigins = origins.getOrPut(n.key) { mutableMapOf() }
                        currentOrigins[rule.id] = Origin(recordId, rule.name)
                        val dueAt = planner.plan(n.key)!!.deadlines.getValue(rule.id) - SystemClock.elapsedRealtime() + System.currentTimeMillis()
                        repository.dao.putDismissPlan(DismissRow(n.key, rule.id, update.active.revision, RuleJson.encodeToString(n), recordId, rule.name, dueAt))
                        repository.action(recordId, rule.name, "消除", "PENDING", "已计划延迟 " + action.delayMs + " ms 消除")
                    }
                } }
            }
        } catch (e: Exception) {
            planner.removed(n.key); origins.remove(n.key)
            throw e
        }
        dismissDue()
    }
    private suspend fun onRemoved(n: NotificationSnapshot) {
        val current = planner.current(n.key)?.snapshot
        if (current != null && (!current.sameContent(n) || n.postedAt < current.postedAt)) return
        val attempted = awaitingRemoval.remove(n.key)?.second
        if (attempted != null) attempted.forEach {
            repository.action(it.recordId, it.ruleName, "消除", "REMOVED", "系统已报告通知移除；不能据此确认移除来源")
        }
        invalidate(n.key, "通知已被用户、应用或系统移除")
        planner.removed(n.key)
        repository.markNotificationRemoved(n)
    }
    private suspend fun dismissDue() {
        val listener = service ?: return
        for (oldPlan in planner.due(SystemClock.elapsedRealtime())) {
            val allowed = repository.allRules().filter { it.enabled && it.applications.isNotEmpty() && it.migrationIssues.isEmpty() }.map { it.id }.toSet()
            val cancelled = oldPlan.ruleIds - allowed
            cancelled.forEach { id ->
                repository.dao.deleteDismissPlan(oldPlan.key, id)
                origins[oldPlan.key]?.remove(id)?.let { repository.action(it.recordId, it.ruleName, "消除", "CANCELLED", "规则已关闭或删除，取消消除") }
            }
            val plan = planner.retainRules(oldPlan.key, allowed) ?: continue
            if (plan.dueAt > SystemClock.elapsedRealtime()) continue
            val active = planner.current(plan.key) ?: continue
            // 切回回调所在主线程，核对当前系统内容后才取消，避免队列积压导致误删新通知。
            val result = withContext(Dispatchers.Main.immediate) {
                try {
                    val current = listener.activeNotifications.firstOrNull { it.key == plan.key }
                    when {
                        current == null -> "MISSING"
                        !active.snapshot.sameContent(listener.snapshot(current)) -> "STALE"
                        else -> { listener.cancelNotification(plan.key); "REQUESTED" }
                    }
                } catch (_: Exception) { "FAILED" }
            }
            planner.consume(plan.key, plan.revision)
            repository.dao.deleteDismissPlans(plan.key)
            val sources = origins.remove(plan.key)?.values?.toList().orEmpty()
            if (result == "REQUESTED") awaitingRemoval[plan.key] = SystemClock.elapsedRealtime() to sources
            sources.forEach { repository.action(it.recordId, it.ruleName, "消除", result,
                when (result) { "REQUESTED" -> "已请求系统消除，等待移除回调"; "STALE" -> "内容已变化，取消旧任务"; "MISSING" -> "通知已不在活跃列表"; else -> "系统未接受消除请求" }) }
        }
    }
}
