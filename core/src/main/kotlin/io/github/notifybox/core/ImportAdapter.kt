package io.github.notifybox.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

object ImportAdapter {
    fun preview(source: String): ImportReport {
        require(source.toByteArray().size <= 2 * 1024 * 1024) { "导入文件不能超过 2 MiB" }
        val element = RuleJson.parseToJsonElement(source)
        if (element is JsonObject && element["format"]?.jsonPrimitive?.contentOrNull == "notifybox") {
            val bundle = RuleJson.decodeFromString<RuleBundle>(source)
            require(bundle.schemaVersion in 1..2) { "不支持的规则格式版本" }
            require(bundle.rules.map { it.id }.distinct().size == bundle.rules.size && bundle.profiles.map { it.id }.distinct().size == bundle.profiles.size) { "规则或配置 ID 重复" }
            val profileIds = bundle.profiles.map { it.id }.toSet()
            require(bundle.profileConfigs.keys.all { it in profileIds }) { "转发配置缺少对应名称" }
            bundle.profiles.forEach { p -> bundle.profileConfigs[p.id]?.let { c ->
                require(Templates.validate(p.kind, c).isEmpty()) { "转发配置无效，请检查导入文件" }
            } }
            require(bundle.rules.flatMap { it.actions }.filterIsInstance<WebhookAction>().all { it.profileId in profileIds }) { "缺少转发配置元数据" }
            require(bundle.rules.size <= 500 && bundle.profiles.size <= 200) { "规则或配置数量过多" }
            return ImportReport(bundle.rules.map { r ->
                val blockers = RuleValidator.errors(r) + r.migrationIssues
                ImportItem(r.copy(enabled = false), listOf("导入后保持关闭，请检查后启用") +
                    if (r.actions.filterIsInstance<WebhookAction>().any { it.profileId !in bundle.profileConfigs }) listOf("旧文件缺少完整转发配置，需要补填") else emptyList(), blockers.distinct())
            }, bundle.profiles, profileConfigs = bundle.profileConfigs)
        }
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["rules"] ?: element["filters"]) as? JsonArray
            else -> null
        } ?: error("未识别规则列表；支持 NotifyBox 格式、旧格式数组或 rules/filters 数组")
        require(array.size <= 500) { "一次最多导入 500 条规则" }
        val profiles = mutableListOf<ProfileMetadata>()
        val items = array.mapIndexed { index, raw ->
            val obj = raw as? JsonObject ?: error("规则必须是 JSON 对象")
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.take(120) ?: "导入规则 " + (index + 1)
            val notes = mutableListOf("旧格式规则默认禁用，请确认匹配范围后启用", "不迁移复制、已读等通知按钮操作")
            val blockers = mutableListOf<String>()
            // 仅接受显式 packageName/userId 对象，不推断内部数字枚举或复合字符串编码。
            val apps = mutableListOf<ApplicationTarget>()
            val rawApps = obj["applications"] as? JsonArray
            if (rawApps == null) blockers += "应用范围字段未确认，请重新填写包名和用户标识"
            else rawApps.forEach { a ->
                val target = a as? JsonObject
                val pkg = (target?.get("packageName") as? JsonPrimitive)?.contentOrNull
                val user = target?.get("userId")
                val userId = (user as? JsonPrimitive)?.intOrNull
                if (pkg.isNullOrBlank() || (user != null && user != JsonNull && userId == null)) blockers += "存在无法识别的应用或用户标识"
                else apps += ApplicationTarget(pkg, userId)
            }
            val actions = mutableListOf<RuleAction>()
            var condition = TextCondition()
            when (name) {
                "全部消除" -> {
                    actions += DismissAction(4000, true)
                    notes += "按已确认需求不限文本，忽略残留 strs/strs1 等文本列表；延迟 4000 ms，尝试处理常驻通知"
                }
                "部分消除" -> {
                    actions += DismissAction(4000, true)
                    blockers += "strs1 和文本匹配字段尚未确认，请重新填写关键词并选择标题/正文"
                }
                "微信转发至 tg", "短信转发至 tg" -> {
                    val profile = ProfileMetadata(name = name, kind = ProfileKind.TELEGRAM)
                    profiles += profile
                    actions += WebhookAction(profile.id)
                    notes += "重建为 Telegram POST JSON；新去重窗口 5 秒，原 distinct 算法未还原"
                    blockers += "需要填写新的 Bot Token 和 chat_id"
                    if (name == "短信转发至 tg") condition = TextCondition(
                        operator = TextOperator.NOT_CONTAINS, values = listOf("点按即可了解详情或停止应用"))
                }
                else -> blockers += "未确认该规则的条件和动作，请在编辑页重新配置"
            }
            if (obj.containsKey("dismissed_notify")) blockers += "dismissed_notify 的提醒语义未确认，无法迁移"
            val handled = setOf("name", "applications", "strs", "strs1", "dismissed_notify")
            val otherFields = obj.keys.filter { it !in handled }
            if (otherFields.isNotEmpty()) blockers += "存在未确认字段或内部枚举：" + otherFields.joinToString("、") { it.take(50) }
            if (containsCrlfUrl(obj)) notes += "原 URL 包含换行；旧地址和凭证未保存，请在新配置中确认并重新填写"
            val rule = Rule(name = name, enabled = false, applications = apps, textCondition = condition, actions = actions,
                migrationIssues = blockers.distinct())
            ImportItem(rule, notes, blockers.distinct())
        }
        return ImportReport(items, profiles, listOf("未提供原始导出样本，未确认的旧格式字段不推测含义；旧凭证不保存"))
    }
    private fun containsCrlfUrl(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (k, v) ->
            (k.contains("url", true) && (v as? JsonPrimitive)?.contentOrNull?.let { '\r' in it || '\n' in it } == true) || containsCrlfUrl(v)
        }
        is JsonArray -> element.any(::containsCrlfUrl)
        else -> false
    }
}
