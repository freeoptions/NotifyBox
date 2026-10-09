package io.github.notifybox.core

// 所有调用由通知处理器的串行协程管理。
class DismissPlanner {
    data class Active(val snapshot: NotificationSnapshot, val revision: Long)
    data class Plan(val key: String, val revision: Long, val dueAt: Long, val ruleIds: Set<String>, val deadlines: Map<String, Long>)
    data class Update(val active: Active, val changed: Boolean, val arrived: Boolean)
    private val active = mutableMapOf<String, Active>()
    private val plans = mutableMapOf<String, Plan>()
    private var nextRevision = 0L
    fun restore(n: NotificationSnapshot, revision: Long) {
        active[n.key] = Active(n, revision)
        nextRevision = maxOf(nextRevision, revision)
    }
    fun posted(n: NotificationSnapshot): Update {
        val previous = active[n.key]
        if (previous != null && previous.snapshot.sameContent(n)) return Update(previous, false, false)
        plans.remove(n.key)
        val current = Active(n, ++nextRevision)
        active[n.key] = current
        return Update(current, true, previous == null)
    }
    fun schedule(key: String, revision: Long, dueAt: Long, ruleId: String): Plan {
        require(active[key]?.revision == revision)
        val old = plans[key]
        val deadlines = (old?.deadlines ?: emptyMap()).toMutableMap()
        deadlines[ruleId] = minOf(deadlines[ruleId] ?: Long.MAX_VALUE, dueAt)
        val plan = Plan(key, revision, deadlines.values.min(), deadlines.keys.toSet(), deadlines)
        plans[key] = plan
        return plan
    }
    fun nextDueAt(): Long? = plans.values.minOfOrNull { it.dueAt }
    fun retainRules(key: String, allowedIds: Set<String>): Plan? {
        val old = plans[key] ?: return null
        val remaining = old.deadlines.filterKeys { it in allowedIds }
        if (remaining.isEmpty()) { plans.remove(key); return null }
        val plan = old.copy(dueAt = remaining.values.min(), ruleIds = remaining.keys, deadlines = remaining)
        plans[key] = plan
        return plan
    }
    fun current(key: String) = active[key]
    fun plan(key: String) = plans[key]
    fun due(now: Long) = plans.values.filter { it.dueAt <= now }.toList()
    fun consume(key: String, revision: Long): Plan? {
        val plan = plans[key]?.takeIf { it.revision == revision && active[key]?.revision == revision } ?: return null
        plans.remove(key)
        return plan
    }
    fun removed(key: String) { active.remove(key); plans.remove(key) }
    fun clear() { active.clear(); plans.clear() }
}
