package io.github.notifybox.listener

import android.content.Context
import android.os.SystemClock
import android.os.PowerManager
import androidx.room.withTransaction
import io.github.notifybox.core.*
import io.github.notifybox.data.*
import io.github.notifybox.worker.QueueScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
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
        data class RulesChanged(val ids: Set<String>) : Command
        data object CheckListener : Command
        data object Tick : Command
    }
    private val channel = Channel<Command>(512)
    private val planner = DismissPlanner()
    private val engine = RuleEngine()
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotifyBox:notification-processing").apply { setReferenceCounted(false) }
    private val queuedWakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotifyBox:notification-queue").apply { setReferenceCounted(false) }
    private val queuedWake = QueuedWakeGuard(
        acquire = { queuedWakeLock.acquire(60_000) },
        release = { if (queuedWakeLock.isHeld) queuedWakeLock.release() },
    )
    private var service: NotificationCollector? = null
    private data class Origin(val recordId: String, val ruleName: String)
    private val origins = mutableMapOf<String, MutableMap<String, Origin>>()
    private val awaitingRemoval = mutableMapOf<String, Pair<Long, List<Origin>>>()
    private val pendingRuleChanges = mutableSetOf<String>()
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
                        is Command.RulesChanged -> recheckRules(command.ids)
                        Command.CheckListener -> checkListener()
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
                    if (command is Command.Connected) {
                        service = null
                        ListenerState.connection(false)
                        BackgroundService.requestListener(context)
                    }
                    ListenerState.error.value = "有一条通知处理失败，请检查存储或配置"
                    delay(1000)
                } finally {
                    if (command != Command.Tick) queuedWake.complete()
                }
            }
        }
        scope.launch {
            try { ready.await() } catch (_: Exception) { return@launch }
            var previous = emptyMap<String, Rule>()
            repository.rules.collect { rules ->
                val current = rules.associateBy { it.id }
                // Room 在保存相同内容时也会通知；这种保存仍重新检查现存通知。
                val changed = (previous.keys + current.keys).filter { previous[it] != current[it] }.toSet().ifEmpty { current.keys }
                if (changed.isNotEmpty()) {
                    queuedWake.enqueue()
                    // 规则变化不能因通知队列暂满而丢弃。
                    try { channel.send(Command.RulesChanged(changed)) }
                    catch (e: Exception) { queuedWake.complete(); throw e }
                }
                previous = current
            }
        }
    }
    private fun send(command: Command) {
        queuedWake.enqueue()
        if (channel.trySend(command).isFailure) {
            queuedWake.complete()
            ListenerState.dropped.value += 1
            ListenerState.error.value = "通知过于密集，处理队列已满；部分事件未采集"
        }
    }
    fun connect(value: NotificationCollector) = send(Command.Connected(value))
    fun disconnect(value: NotificationCollector) = send(Command.Disconnected(value))
    fun posted(n: NotificationSnapshot) = send(Command.Posted(n))
    fun removed(n: NotificationSnapshot) = send(Command.Removed(n))
    fun checkListenerHealth() = send(Command.CheckListener)
    private suspend fun checkListener() {
        val listener = service ?: run {
            BackgroundService.requestListener(context)
            return
        }
        val current = try {
            withContext(Dispatchers.Main.immediate) {
                listener.activeNotifications.filter { it.packageName != context.packageName }.map { listener.snapshot(it) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            service = null
            ListenerState.connection(false)
            ListenerState.error.value = "通知监听失去连接，正在恢复"
            BackgroundService.requestListener(context)
            return
        }
        // 健康检查也能补收漏掉的回调；已处理的相同内容不入队转发、不重置延迟。
        current.forEach { n ->
            if (planner.current(n.key)?.snapshot?.sameContent(n) != true) onPosted(n, recovered = true)
        }
        dismissDue()
    }
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
        // 重连先按最新规则校正旧计划，再执行；规则关闭、改条件或移除消除动作也会生效。
        recheckRules()
    }
    private suspend fun onPosted(n: NotificationSnapshot, recovered: Boolean = false) {
        if (n.packageName == context.packageName) return
        val update = planner.posted(n)
        if (!update.changed) return
        invalidate(n.key, "通知内容已更新，旧消除任务失效")
        val matched = repository.allRules().filter { engine.matches(it, n) }
        val recordId = newId()
        val forwardingTasks = mutableListOf<TaskRow>()
        try {
            repository.db.withTransaction {
                repository.dao.addNotification(repository.indexed(NotificationRow(recordId, n.key, update.active.revision,
                    if (update.arrived) EventKind.ARRIVED.name else EventKind.UPDATED.name,
                    RuleJson.encodeToString(n), matched.joinToString("、") { it.name }, n.receivedAt), n))
                // 先持久化所有转发任务，再调度消除；网络执行完全独立。
                matched.forEach { rule -> rule.actions.filterIsInstance<WebhookAction>().forEach { action ->
                    try { repository.enqueue(recordId, rule, action, n)?.let { forwardingTasks += it } } catch (_: Exception) { repository.action(recordId, rule.name, "转发", "FAILED", "配置、模板或本地加密失败，请检查转发配置") }
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
        // 加急 Worker 可能马上执行，不能在外层事务提交前启动并误判任务不存在。
        forwardingTasks.forEach { QueueScheduler.schedule(context, it.id, it.nextRunAt) }
        if (!recovered) dismissDue()
    }
    private suspend fun recheckRules(changedIds: Set<String>? = null) {
        if (changedIds != null) pendingRuleChanges += changedIds
        val listener = service ?: return
        val rules = repository.allRules()
        val changed = (if (changedIds == null) pendingRuleChanges + rules.map { it.id } + origins.values.flatMap { it.keys }
            else pendingRuleChanges).toSet()
        val current = withContext(Dispatchers.Main.immediate) {
            listener.activeNotifications.filter { it.packageName != context.packageName }.map { listener.snapshot(it) }
        }
        for (n in current) {
            if (n.key in awaitingRemoval) continue
            // 尚未处理的到达/内容更新交给队列中的 Posted，避免吞掉正常的新通知转发。
            val result = planner.recheck(n, rules, changed, SystemClock.elapsedRealtime()) ?: continue
            if (result.matched.isEmpty() && result.cancelled.isEmpty()) continue
            val previousOrigins = origins[n.key].orEmpty()
            var recordId: String? = null
            repository.db.withTransaction {
                result.cancelled.forEach { id ->
                    repository.dao.deleteDismissPlan(n.key, id)
                    previousOrigins[id]?.let { repository.action(it.recordId, it.ruleName, "消除", "CANCELLED", "规则已更新，不再对这条通知执行消除") }
                }
                if (result.matched.isNotEmpty()) {
                    val last = repository.dao.latestNotification(n.key)?.takeIf {
                        it.kind != EventKind.REMOVED.name && !repository.dao.notificationWasRemoved(it.id) &&
                            RuleJson.decodeFromString<NotificationSnapshot>(it.snapshotJson).sameContent(n)
                    }
                    val snapshot = result.active.snapshot
                    val names = (last?.matchedNames.orEmpty().split('、').filter { it.isNotBlank() } + result.matched.map { it.name }).distinct().joinToString("、")
                    val row = last?.copy(matchedNames = names) ?: NotificationRow(newId(), n.key, result.active.revision,
                        EventKind.ARRIVED.name, RuleJson.encodeToString(snapshot), names, snapshot.receivedAt)
                    recordId = row.id
                    val indexed = repository.indexed(row, snapshot)
                    if (last == null) repository.dao.addNotification(indexed) else repository.dao.updateNotification(indexed)
                    result.matched.forEach { rule ->
                        val due = result.plan!!.deadlines.getValue(rule.id)
                        val dueAt = due - SystemClock.elapsedRealtime() + System.currentTimeMillis()
                        repository.dao.putDismissPlan(DismissRow(n.key, rule.id, result.active.revision,
                            RuleJson.encodeToString(snapshot), row.id, rule.name, dueAt))
                        if (rule.id in result.changedDeadlines) repository.action(row.id, rule.name, "消除", "PENDING", "规则已生效，准备消除当前通知")
                    }
                }
            }
            planner.applyRecheck(result)
            val updatedOrigins = origins.getOrPut(n.key) { mutableMapOf() }
            result.cancelled.forEach { updatedOrigins.remove(it) }
            result.matched.forEach { rule -> updatedOrigins[rule.id] = Origin(recordId!!, rule.name) }
            if (updatedOrigins.isEmpty()) origins.remove(n.key)
        }
        pendingRuleChanges.removeAll(changed)
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
            val active = planner.current(oldPlan.key) ?: continue
            // 即使规则变化事件仍在队列中，也不能执行已不再匹配或已移除的消除动作。
            val allowed = repository.allRules().filter { rule ->
                engine.matches(rule, active.snapshot) && rule.actions.filterIsInstance<DismissAction>()
                    .any { !active.snapshot.ongoing || it.includeOngoing }
            }.map { it.id }.toSet()
            val cancelled = oldPlan.ruleIds - allowed
            cancelled.forEach { id ->
                repository.dao.deleteDismissPlan(oldPlan.key, id)
                origins[oldPlan.key]?.remove(id)?.let { repository.action(it.recordId, it.ruleName, "消除", "CANCELLED", "规则已关闭、删除或不再匹配，取消消除") }
            }
            val plan = planner.retainRules(oldPlan.key, allowed) ?: continue
            if (plan.dueAt > SystemClock.elapsedRealtime()) continue
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
