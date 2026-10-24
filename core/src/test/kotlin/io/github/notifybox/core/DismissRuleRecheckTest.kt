package io.github.notifybox.core

import org.junit.Assert.*
import org.junit.Test

class DismissRuleRecheckTest {
    private val planner = DismissPlanner()
    private val n = NotificationSnapshot("key", "sample.app", 0, title = "订单", text = "已送达", postedAt = 10, receivedAt = 20)
    private fun rule(id: String = "a", delay: Long = 0) = Rule(id = id, name = "消除$id",
        applications = listOf(ApplicationTarget(n.packageName)), actions = listOf(DismissAction(delay, true)))
    private fun apply(rules: List<Rule>, ids: Set<String> = setOf("a"), now: Long = 1000): DismissPlanner.Recheck {
        val result = planner.recheck(n, rules, ids, now)!!
        planner.applyRecheck(result)
        return result
    }

    @Test fun newlySavedImmediateRuleAppliesToExistingNotification() {
        val active = planner.posted(n).active
        val result = planner.recheck(n, listOf(rule()), setOf("a"), 1000)!!
        assertNull(planner.plan(n.key)) // 持久化前不改变计划。
        planner.applyRecheck(result)
        assertEquals(active.revision, result.plan!!.revision)
        assertEquals(1000L, planner.due(1000).single().dueAt)
        assertFalse(planner.posted(n.copy(receivedAt = 2000)).changed)
    }

    @Test fun enablingRuleStartsDismissalForExistingNotification() {
        planner.posted(n)
        apply(listOf(rule().copy(enabled = false)))
        assertNull(planner.plan(n.key))
        apply(listOf(rule()))
        assertEquals(setOf("a"), planner.due(1000).single().ruleIds)
    }

    @Test fun repeatedSaveDoesNotExtendExistingDelay() {
        planner.posted(n)
        apply(listOf(rule(delay = 5000)), now = 1000)
        val result = apply(listOf(rule(delay = 5000)), now = 3000)
        assertEquals(6000L, result.plan!!.dueAt)
        assertTrue(result.changedDeadlines.isEmpty())
        assertTrue(planner.due(5999).isEmpty())
        assertEquals(1, planner.due(6000).size)
    }

    @Test fun changingDelayToImmediateKeepsOtherRuleDeadline() {
        planner.posted(n)
        apply(listOf(rule(delay = 5000), rule("b", 10000)), setOf("a", "b"))
        val result = apply(listOf(rule(), rule("b", 10000)), now = 2000)
        assertEquals(mapOf("a" to 2000L, "b" to 11000L), result.plan!!.deadlines)
        assertEquals(setOf("a"), result.changedDeadlines)
    }

    @Test fun editedConditionCancelsOnlyAffectedRule() {
        planner.posted(n)
        apply(listOf(rule(delay = 5000), rule("b", 10000)), setOf("a", "b"))
        val changed = rule().copy(textCondition = TextCondition(values = listOf("不会匹配")))
        val result = apply(listOf(changed, rule("b", 10000)))
        assertEquals(setOf("a"), result.cancelled)
        assertEquals(setOf("b"), result.plan!!.ruleIds)
        assertEquals(11000L, result.plan!!.dueAt)
    }

    @Test fun removedOrDisabledRuleCancelsExistingPlan() {
        planner.posted(n)
        apply(listOf(rule(delay = 5000)))
        assertEquals(setOf("a"), apply(emptyList()).cancelled)
        assertNull(planner.plan(n.key))
        apply(listOf(rule(delay = 5000)))
        assertEquals(setOf("a"), apply(listOf(rule().copy(enabled = false))).cancelled)
        assertNull(planner.plan(n.key))
    }

    @Test fun appUserAndMigrationRestrictionsStillApply() {
        planner.posted(n)
        val excluded = listOf(rule().copy(applications = emptyList()),
            rule().copy(applications = listOf(ApplicationTarget("other.app"))),
            rule().copy(applications = listOf(ApplicationTarget(n.packageName, 999))),
            rule().copy(migrationIssues = listOf("待确认")))
        excluded.forEach { assertTrue(apply(listOf(it)).matched.isEmpty()) }
        assertNull(planner.plan(n.key))
    }

    @Test fun ongoingNotificationsRespectRuleSetting() {
        val ongoing = n.copy(ongoing = true)
        planner.posted(ongoing)
        val excluded = rule().copy(actions = listOf(DismissAction(0, false)))
        assertTrue(planner.recheck(ongoing, listOf(excluded), setOf("a"), 1000)!!.matched.isEmpty())
        val included = planner.recheck(ongoing, listOf(rule()), setOf("a"), 1000)!!
        planner.applyRecheck(included)
        assertEquals(1, planner.due(1000).size)
    }

    @Test fun unprocessedOrUpdatedNotificationIsLeftForPostedCallback() {
        assertNull(planner.recheck(n, listOf(rule()), setOf("a"), 1000))
        assertNull(planner.current(n.key))
        assertTrue(planner.posted(n).changed)
        assertNull(planner.recheck(n.copy(text = "新内容"), listOf(rule()), setOf("a"), 1000))
        assertTrue(planner.posted(n.copy(text = "新内容")).changed)
    }

    @Test fun staleRecheckCannotBeAppliedAfterContentRevisionChanges() {
        planner.posted(n)
        val result = planner.recheck(n, listOf(rule()), setOf("a"), 1000)!!
        planner.posted(n.copy(text = "新内容"))
        assertThrows(IllegalArgumentException::class.java) { planner.applyRecheck(result) }
        assertNull(planner.plan(n.key))
    }

    @Test fun forwardingOnlyRuleCreatesNoDismissalAndMixedRuleOnlyPlansDismissal() {
        planner.posted(n)
        val webhook = WebhookAction("sample-profile")
        assertTrue(apply(listOf(rule().copy(actions = listOf(webhook)))).matched.isEmpty())
        assertNull(planner.plan(n.key))
        val mixed = rule().copy(actions = listOf(webhook, DismissAction(5000), DismissAction(0)))
        assertEquals(1000L, apply(listOf(mixed)).plan!!.dueAt)
        assertEquals(setOf("a"), planner.plan(n.key)!!.ruleIds)
    }

    @Test fun restoredDeadlineIsPreservedAcrossRuleReconciliation() {
        planner.restore(n, 17)
        planner.schedule(n.key, 17, 900, "a")
        val result = apply(listOf(rule(delay = 5000)))
        assertEquals(900L, result.plan!!.dueAt)
        assertEquals(17L, result.plan!!.revision)
        assertTrue(result.changedDeadlines.isEmpty())
    }
}
