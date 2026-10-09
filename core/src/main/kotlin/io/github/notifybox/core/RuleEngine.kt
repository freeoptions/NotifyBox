package io.github.notifybox.core

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

object RuleValidator {
    fun errors(rule: Rule): List<String> = buildList {
        if (rule.schemaVersion != 1) add("规则版本不支持")
        if (rule.name.isBlank()) add("请填写规则名称")
        if (rule.name.length > 120) add("规则名称不能超过 120 字")
        if (rule.applications.any { it.packageName.isBlank() || it.userId?.let { id -> id < 0 } == true }) add("应用包名或用户标识无效")
        if (rule.actions.isEmpty()) add("至少选择一个动作")
        if (rule.actions.size > 16) add("一条规则最多 16 个动作")
        val c = rule.textCondition
        if (c.values.isNotEmpty() && (c.fields.isEmpty() || c.values.any { it.isEmpty() })) add("文本字段和条件不能留空")
        if (c.values.size > 64 || c.values.any { it.length > 2048 }) add("最多 64 个条件，每个最多 2048 字")
        if (c.operator == TextOperator.CONTAINS && c.values.size > 1) add("包含仅接受一个条件；多条件请使用任一或全部")
        if (c.operator == TextOperator.REGEX) c.values.forEach { value ->
            try { Pattern.compile(value, if (c.ignoreCase) Pattern.CASE_INSENSITIVE else 0) } catch (_: PatternSyntaxException) { add("正则无效或使用了不支持的环视/反向引用") }
        }
        rule.actions.forEach { action -> when (action) {
            is DismissAction -> if (action.delayMs !in 0..86_400_000) add("消除延迟需在 0 到 86400000 毫秒之间")
            is WebhookAction -> {
                if (action.profileId.isBlank()) add("请选择转发配置")
                if (action.dedupWindowSeconds !in 1..86400) add("去重窗口需在 1 到 86400 秒之间")
            }
        } }
    }
}
class RuleEngine {
    private val patterns = object : LinkedHashMap<String, Pattern>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?) = size > 128
    }
    private val validity = object : LinkedHashMap<Rule, Boolean>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Rule, Boolean>?) = size > 128
    }
    fun matches(rule: Rule, n: NotificationSnapshot, requireEnabled: Boolean = true): Boolean {
        if ((requireEnabled && !rule.enabled) || rule.migrationIssues.isNotEmpty()) return false
        if (!synchronized(validity) { validity.getOrPut(rule) { RuleValidator.errors(rule).isEmpty() } }) return false
        if (rule.applications.none {
            it.packageName == n.packageName && (it.userId == null || it.userId == n.userId)
        }) return false
        val c = rule.textCondition
        if (c.values.isEmpty()) return true
        val fields = c.fields.map { if (it == TextField.TITLE) n.title else n.text }
        fun contains(value: String) = fields.any { it.contains(value, ignoreCase = c.ignoreCase) }
        return when (c.operator) {
            TextOperator.CONTAINS, TextOperator.ANY -> c.values.any(::contains)
            TextOperator.NOT_CONTAINS -> c.values.none(::contains)
            TextOperator.ALL -> c.values.all(::contains)
            TextOperator.REGEX -> c.values.any { value ->
                val key = c.ignoreCase.toString() + ":" + value
                val pattern = synchronized(patterns) { patterns.getOrPut(key) { Pattern.compile(value, if (c.ignoreCase) Pattern.CASE_INSENSITIVE else 0) } }
                fields.any { pattern.matcher(it).find() }
            }
        }
    }
    fun trial(rules: List<Rule>, n: NotificationSnapshot) = rules.map { rule ->
        TrialResult(rule.id, rule.name, matches(rule, n, requireEnabled = false), RuleValidator.errors(rule) + rule.migrationIssues)
    }
}
data class TrialResult(val id: String, val name: String, val matched: Boolean, val issues: List<String>)
