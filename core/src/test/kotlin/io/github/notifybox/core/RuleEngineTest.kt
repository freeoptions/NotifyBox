package io.github.notifybox.core

import org.junit.Assert.*
import org.junit.Test

class RuleEngineTest {
    private val engine = RuleEngine()
    private val n = NotificationSnapshot("k", "com.example.sms", 0, title = "验证码 ABC", text = "1234 登录", postedAt = 1, receivedAt = 2)
    private fun rule(c: TextCondition = TextCondition(), apps: List<ApplicationTarget> = listOf(ApplicationTarget(n.packageName))) =
        Rule(name = "测试", enabled = true, applications = apps, textCondition = c, actions = listOf(DismissAction()))
    @Test fun noApplicationsNeverMatchesEvenWhenTextMatchesOrTrialIgnoresEnabled() {
        val noApps = rule(apps = emptyList())
        assertFalse(engine.matches(noApps, n))
        assertFalse(engine.matches(noApps.copy(textCondition = TextCondition(values = listOf("验证码"))), n))
        assertFalse(engine.matches(noApps, n.copy(packageName = "com.other", userId = 999)))
        assertFalse(engine.trial(listOf(noApps.copy(enabled = false)), n).single().matched)
    }
    @Test fun emptyConditionMatchesEvenEmptyText() { assertTrue(engine.matches(rule(), n.copy(title = "", text = ""))) }
    @Test fun anyCanMatchEitherField() { assertTrue(engine.matches(rule(TextCondition(values = listOf("不存在", "登录"))), n)) }
    @Test fun allCanSpanFields() { assertTrue(engine.matches(rule(TextCondition(operator = TextOperator.ALL, values = listOf("验证码", "1234"))), n)) }
    @Test fun allRejectsMissingWord() { assertFalse(engine.matches(rule(TextCondition(operator = TextOperator.ALL, values = listOf("验证码", "不存在"))), n)) }
    @Test fun exclusionRejectsAnyExcludedWord() { assertFalse(engine.matches(rule(TextCondition(operator = TextOperator.NOT_CONTAINS, values = listOf("不存在", "登录"))), n)) }
    @Test fun caseSensitiveIsOptional() {
        assertTrue(engine.matches(rule(TextCondition(values = listOf("abc"))), n))
        assertFalse(engine.matches(rule(TextCondition(values = listOf("abc"), ignoreCase = false)), n))
    }
    @Test fun fieldSelectionIsRespected() { assertFalse(engine.matches(rule(TextCondition(fields = setOf(TextField.TITLE), values = listOf("1234"))), n)) }
    @Test fun cloneUserIsIndependent() {
        assertFalse(engine.matches(rule(apps = listOf(ApplicationTarget(n.packageName, 999))), n))
        assertTrue(engine.matches(rule(apps = listOf(ApplicationTarget(n.packageName, null))), n.copy(userId = 999)))
    }
    @Test fun invalidOrUnsafeRegexBlocksSaving() {
        assertTrue(RuleValidator.errors(rule(TextCondition(operator = TextOperator.REGEX, values = listOf("(?<=x)y")))).isNotEmpty())
        assertFalse(engine.matches(rule(TextCondition(operator = TextOperator.REGEX, values = listOf("["))), n))
    }
    @Test fun validRegexFindsSubstring() { assertTrue(engine.matches(rule(TextCondition(operator = TextOperator.REGEX, values = listOf("[0-9]{4}"))), n)) }
    @Test fun trialIncludesDisabledRuleButDoesNotBypassMigration() {
        assertTrue(engine.trial(listOf(rule().copy(enabled = false)), n).single().matched)
        assertFalse(engine.trial(listOf(rule().copy(migrationIssues = listOf("待确认"))), n).single().matched)
    }
    @Test fun notificationCanMatchDismissAndForwardRules() {
        val rules = listOf(rule(), rule().copy(id = "forward", actions = listOf(WebhookAction("target"))))
        assertEquals(2, rules.count { engine.matches(it, n) })
    }
}
