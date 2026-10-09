package io.github.notifybox.core

import org.junit.Assert.*
import org.junit.Test

class DismissPlannerTest {
    private val planner = DismissPlanner()
    private val n = NotificationSnapshot("k", "com.example", 0, title = "标题", text = "正文", postedAt = 1, receivedAt = 2)
    @Test fun restoredDeadlineSurvivesRepeatedCallbackAndRevisionContinues() {
        planner.restore(n, 17)
        planner.schedule(n.key, 17, 4000, "rule")
        assertFalse(planner.posted(n.copy(receivedAt = 3000)).changed)
        assertEquals(4000L, planner.plan(n.key)!!.dueAt)
        assertTrue(planner.posted(n.copy(text = "更新")).active.revision > 17)
        assertNull(planner.plan(n.key))
    }
    @Test fun earliestDeadlineWinsAndRulesAreMerged() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 4000, "a")
        val plan = planner.schedule("k", rev, 3000, "b")
        assertEquals(3000L, plan.dueAt); assertEquals(setOf("a", "b"), plan.ruleIds)
    }
    @Test fun repeatedCallbackDoesNotResetTimer() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 4000, "a")
        val repeated = planner.posted(n.copy(postedAt = 100, receivedAt = 200))
        assertFalse(repeated.changed); assertEquals(rev, repeated.active.revision)
        assertEquals(4000L, planner.plan("k")!!.dueAt)
    }
    @Test fun contentUpdateInvalidatesOldRevision() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 4000, "a")
        val updated = planner.posted(n.copy(text = "新正文"))
        assertTrue(updated.changed); assertNotEquals(rev, updated.active.revision)
        assertNull(planner.plan("k")); assertNull(planner.consume("k", rev))
    }
    @Test fun removalCancelsPlan() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 4000, "a")
        planner.removed("k")
        assertTrue(planner.due(5000).isEmpty())
    }
    @Test fun reusedKeyHasNewRevision() {
        val old = planner.posted(n).active.revision
        planner.removed(n.key)
        assertNotEquals(old, planner.posted(n).active.revision)
    }
    @Test fun duePlanIsConsumedOnlyOnce() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 4000, "a")
        assertTrue(planner.due(3999).isEmpty())
        assertEquals(1, planner.due(4000).size)
        assertNotNull(planner.consume("k", rev)); assertNull(planner.consume("k", rev))
    }

    @Test fun disablingEarliestRuleRecalculatesRemainingDeadline() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 1000, "a")
        planner.schedule("k", rev, 4000, "b")
        val remaining = planner.retainRules("k", setOf("b"))!!
        assertEquals(4000L, remaining.dueAt)
        assertEquals(setOf("b"), remaining.ruleIds)
        assertTrue(planner.due(1000).isEmpty())
    }
    @Test fun deletingAllSourceRulesCancelsPlan() {
        val rev = planner.posted(n).active.revision
        planner.schedule("k", rev, 4000, "a")
        assertNull(planner.retainRules("k", emptySet()))
        assertTrue(planner.due(5000).isEmpty())
    }
}
