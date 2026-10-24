package io.github.notifybox.core

// 所有调用由通知处理器的串行协程管理。
class DismissPlanner {
    data class Active(val snapshot: NotificationSnapshot, val revision: Long)
    data class Plan(val key: String, val revision: Long, val dueAt: Long, val ruleIds: Set<String>, val deadlines: Map<String, Long>)
    data class Update(val active: Active, val changed: Boolean, val arrived: Boolean)
    data class Recheck(val active: Active, val plan: Plan?, val matched: List<Rule>,
        val cancelled: Set<String>, val changedDeadlines: Set<String>)
    private val engine = RuleEngine()
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
    // 只计算消除计划，忽略转发动作。持久化成功后再 applyRecheck，不提前修改内存状态。
    fun recheck(n: NotificationSnapshot, rules: List<Rule>, changedRuleIds: Set<String>, now: Long): Recheck? {
        val current = active[n.key] ?: return null
        if (!current.snapshot.sameContent(n) || n.postedAt < current.snapshot.postedAt) return null
        val delays = rules.filter { it.id in changedRuleIds && engine.matches(it, n) }.mapNotNull { rule ->
            rule.actions.filterIsInstance<DismissAction>().filter { !n.ongoing || it.includeOngoing }
                .minOfOrNull { it.delayMs }?.let { rule to it }
        }
        val previous = plans[n.key]
        val deadlines = previous?.deadlines.orEmpty().filterKeys { it !in changedRuleIds }.toMutableMap()
        delays.forEach { (rule, delay) ->
            deadlines[rule.id] = minOf(previous?.deadlines?.get(rule.id) ?: Long.MAX_VALUE, now + delay)
        }
        val plan = if (deadlines.isEmpty()) null else Plan(n.key, current.revision, deadlines.values.min(), deadlines.keys.toSet(), deadlines.toMap())
        val matched = delays.map { it.first }
        val cancelled = previous?.ruleIds.orEmpty().intersect(changedRuleIds) - matched.map { it.id }.toSet()
        val changed = matched.map { it.id }.filter { previous?.deadlines?.get(it) != deadlines[it] }.toSet()
        return Recheck(current, plan, matched, cancelled, changed)
    }
    fun applyRecheck(result: Recheck) {
        val key = result.active.snapshot.key
        require(active[key]?.revision == result.active.revision)
        if (result.plan == null) plans.remove(key) else plans[key] = result.plan
    }
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
