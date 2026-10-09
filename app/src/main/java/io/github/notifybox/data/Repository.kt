package io.github.notifybox.data

import android.content.Context
import androidx.room.withTransaction
import io.github.notifybox.core.*
import io.github.notifybox.worker.QueueScheduler
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import io.github.notifybox.ui.SearchIndex
import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Locale
import kotlinx.serialization.json.*

class Repository(val db: NotifyDatabase, val vault: SecretVault, private val context: Context) {
    val dao = db.dao()
    val rules = dao.observeRules().map { rows -> rows.map { RuleJson.decodeFromString<Rule>(it.json) } }
    val profiles = dao.observeProfiles()
    val notifications = dao.observeNotifications()
    private val preferences = context.getSharedPreferences("notifybox.settings", Context.MODE_PRIVATE)
    private val mutableThemeColor = MutableStateFlow(preferences.getInt("themeColorRgb", DEFAULT_THEME_RGB) and 0xFFFFFF)
    val themeColor = mutableThemeColor.asStateFlow()
    fun setThemeColor(rgb: Int) {
        require(rgb in 0..0xFFFFFF) { "主题色必须为六位 RGB 色值" }
        preferences.edit().putInt("themeColorRgb", rgb).apply()
        mutableThemeColor.value = rgb
    }
    fun retentionDays() = preferences.getInt("retentionDays", 7).coerceIn(1, 90)
    fun setRetentionDays(days: Int) { require(days in 1..90); preferences.edit().putInt("retentionDays", days).apply() }
    fun continuousRetry() = preferences.getBoolean("continuousRetry", true)
    fun setContinuousRetry(value: Boolean) { preferences.edit().putBoolean("continuousRetry", value).apply() }
    fun exportTree(): String? = preferences.getString("exportTree", null)
    fun setExportTree(value: String?) { preferences.edit().putString("exportTree", value).apply() }
    fun manuallyChecked(key: String) = preferences.getBoolean("permission." + key, false)
    fun setManuallyChecked(key: String, value: Boolean) { preferences.edit().putBoolean("permission." + key, value).apply() }
    fun proxyAddress() = preferences.getString("proxyAddress", "").orEmpty()
    fun setProxyAddress(value: String) {
        if (value.isNotBlank()) {
            val uri = java.net.URI(value)
            require(uri.scheme in listOf("http", "socks") && !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "代理格式为 http://主机:端口 或 socks://主机:端口" }
        }
        preferences.edit().putString("proxyAddress", value).apply()
    }
    fun indexed(row: NotificationRow, n: NotificationSnapshot) = row.copy(
        titleSearch = SearchIndex.indexText(n.title), textSearch = SearchIndex.indexText(n.text),
        appSearch = SearchIndex.indexText(n.appName) + "\n" + n.packageName.lowercase(Locale.ROOT),
        ruleSearch = row.matchedNames.split('、').joinToString("\n") { SearchIndex.indexText(it) })
    fun recordQuery(keyword: String, app: String, rule: String, statuses: List<String>, statusFilter: Boolean, page: Int, count: Boolean = false): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        // 兼容旧版分别保存到达、移除的记录；列表和总数使用相同条件，不删除历史数据。
        val where = mutableListOf("n.kind!='REMOVED'")
        fun add(value: String, expression: String, repeats: Int = 1) {
            if (value.isNotBlank()) { where += expression; repeat(repeats) { args += value.trim().lowercase(Locale.ROOT) } }
        }
        add(keyword, "(instr(n.titleSearch, ?) > 0 OR instr(n.textSearch, ?) > 0)", 2)
        add(app, "instr(n.appSearch, ?) > 0")
        add(rule, "instr(n.ruleSearch, ?) > 0")
        if (statusFilter) {
            where += if (statuses.isEmpty()) "0" else "EXISTS (SELECT 1 FROM actions a WHERE a.notificationId=n.id AND a.status IN (${statuses.joinToString { "?" }}) AND a.rowid=(SELECT MAX(b.rowid) FROM actions b WHERE b.notificationId=a.notificationId AND b.ruleName=a.ruleName AND b.action=a.action))"
            args.addAll(statuses)
        }
        val sql = (if (count) "SELECT COUNT(*)" else "SELECT n.*") + " FROM notifications n" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            (if (count) "" else " ORDER BY n.createdAt DESC, n.rowid DESC LIMIT 20 OFFSET ${page.coerceAtLeast(0) * 20}")
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
    suspend fun allRules() = dao.rules().map { RuleJson.decodeFromString<Rule>(it.json) }
    suspend fun getRule(id: String) = dao.rule(id)?.let { RuleJson.decodeFromString<Rule>(it.json) }
    suspend fun saveRule(rule: Rule, confirmMigration: Boolean = false) {
        val value = if (confirmMigration) rule.copy(migrationIssues = emptyList()) else rule
        require(RuleValidator.errors(value).isEmpty()) { RuleValidator.errors(value).joinToString("；") }
        if (value.enabled) {
            require(value.migrationIssues.isEmpty()) { "请先在编辑页确认导入问题" }
            value.actions.filterIsInstance<WebhookAction>().forEach {
                require(dao.profile(it.profileId)?.configCipher?.isNotEmpty() == true) { "请先补填转发配置" }
            }
        }
        require(dao.rule(value.id) != null || dao.rules().size < 500) { "规则已达 500 条上限" }
        dao.putRule(RuleRow(value.id, RuleJson.encodeToString(value), dao.rule(value.id)?.createdAt ?: System.currentTimeMillis()))
    }
    suspend fun setEnabled(id: String, enabled: Boolean) { getRule(id)?.let { saveRule(it.copy(enabled = enabled)) } }
    suspend fun config(id: String): Pair<ProfileRow, WebhookConfig>? {
        val row = dao.profile(id) ?: return null
        if (row.configCipher.isEmpty()) return null
        return row to RuleJson.decodeFromString<WebhookConfig>(vault.decrypt(row.configCipher))
    }
    suspend fun saveProfile(meta: ProfileMetadata, config: WebhookConfig) {
        require(meta.name.isNotBlank() && meta.name.length <= 120) { "请填写名称（最多 120 字）" }
        val errors = Templates.validate(meta.kind, config)
        require(errors.isEmpty()) { errors.joinToString("；") }
        require(dao.profile(meta.id) != null || dao.profiles().size < 200) { "转发配置已达 200 条上限" }
        dao.putProfile(ProfileRow(meta.id, meta.name, meta.kind.name, vault.encrypt(RuleJson.encodeToString(config))))
    }
    suspend fun import(report: ImportReport) = db.withTransaction {
        require(dao.rules().size + report.items.size <= 500 && dao.profiles().size + report.profiles.size <= 200) { "导入后规则或配置数量超出上限" }
        val mapping = report.profiles.associate { it.id to newId() }
        report.profiles.forEach { p ->
            val cipher = report.profileConfigs[p.id]?.let { vault.encrypt(RuleJson.encodeToString(it)) }.orEmpty()
            dao.putProfile(ProfileRow(mapping.getValue(p.id), p.name, p.kind.name, cipher))
        }
        report.items.forEach { item ->
            val r = item.rule.copy(id = newId(), enabled = false,
                actions = item.rule.actions.map { a -> if (a is WebhookAction) a.copy(profileId = mapping[a.profileId] ?: a.profileId) else a },
                migrationIssues = (item.rule.migrationIssues + item.blockers).distinct())
            dao.putRule(RuleRow(r.id, RuleJson.encodeToString(r), System.currentTimeMillis()))
        }
    }
    suspend fun export(): String {
        val profiles = dao.profiles()
        val configs = profiles.filter { it.configCipher.isNotEmpty() }.associate { p ->
            p.id to RuleJson.decodeFromString<WebhookConfig>(vault.decrypt(p.configCipher))
        }
        val bundle = RuleBundle(schemaVersion = 2, rules = allRules(),
            profiles = profiles.map { ProfileMetadata(it.id, it.name, ProfileKind.valueOf(it.kind)) }, profileConfigs = configs)
        return RuleJson.encodeToString(bundle)
    }
    suspend fun action(notificationId: String, ruleName: String, action: String, status: String, summary: String) {
        dao.addAction(ActionRow(newId(), notificationId, ruleName, action, status, summary, System.currentTimeMillis()))
    }
    suspend fun markNotificationRemoved(n: NotificationSnapshot) {
        val row = dao.latestNotification(n.key) ?: return
        if (row.kind == EventKind.REMOVED.name || dao.notificationWasRemoved(row.id)) return
        val saved = RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson)
        if (!saved.sameContent(n) || n.postedAt < saved.postedAt) return
        // 移除只补充原记录的状态，保留收到时间、内容和关联的转发任务。
        dao.putAction(ActionRow("removed." + row.id, row.id, "", "通知状态", "REMOVED",
            "通知已被移除", n.receivedAt))
    }
    suspend fun deliveryAction(task: TaskRow, status: String, summary: String) {
        if (task.notificationId.isNotEmpty()) dao.putAction(ActionRow("delivery." + task.id, task.notificationId,
            task.ruleName, "转发", status, summary, System.currentTimeMillis()))
    }
    suspend fun enqueue(notificationId: String, rule: Rule, a: WebhookAction, n: NotificationSnapshot): TaskRow? {
        val pair = config(a.profileId) ?: run {
            action(notificationId, rule.name, "转发", "FAILED", "转发配置缺失或尚未补填"); return null
        }
        val request = RequestRenderer.render(ProfileKind.valueOf(pair.first.kind), pair.second, n)
        val key = RequestRenderer.dedupKey(rule.id, a.profileId, n, request)
        val now = System.currentTimeMillis()
        var task: TaskRow? = null
        db.withTransaction {
            if (a.dedupEnabled && dao.duplicate(key, now - a.dedupWindowSeconds * 1000L)) {
                action(notificationId, rule.name, "转发", "DEDUP", "去重窗口内重复，未新增任务")
            } else if (dao.pendingCount() >= 1000) {
                action(notificationId, rule.name, "转发", "FAILED", "待发送任务已达 1000 条，请处理队列")
            } else {
                task = TaskRow(newId(), notificationId, rule.id, rule.name, pair.first.id, pair.first.name,
                    n.packageName, n.userId, vault.encrypt(RuleJson.encodeToString(request)), key, createdAt = now, nextRunAt = now)
                dao.addTask(task!!)
                deliveryAction(task!!, "PENDING", "已写入本地队列")
            }
        }
        task?.let { QueueScheduler.schedule(context, it.id, it.nextRunAt) }
        return task
    }
    suspend fun testProfile(id: String) {
        val pair = config(id) ?: error("请先保存完整配置")
        val now = System.currentTimeMillis()
        val sample = NotificationSnapshot("test", context.packageName, 0, "NotifyBox", "NotifyBox 测试", "这是一条由您主动发送的测试消息。", now, now)
        // 测试强制固定文本，避免用户模板含实际消息或随机读取通知。
        val config = if (pair.first.kind == ProfileKind.TELEGRAM.name) pair.second.copy(messageTemplate = "NotifyBox 测试消息")
            else pair.second
        val r = RequestRenderer.render(ProfileKind.valueOf(pair.first.kind), config, sample)
        val t = TaskRow(newId(), "", "manual-test", "手动测试", id, pair.first.name, context.packageName, 0,
            vault.encrypt(RuleJson.encodeToString(r)), newId(), createdAt = now, nextRunAt = now)
        require(dao.pendingCount() < 1000) { "队列已满" }
        db.withTransaction {
            require(dao.pendingCount() < 1000) { "队列已满" }
            dao.addTask(t)
        }
        QueueScheduler.schedule(context, t.id, now)
    }
    suspend fun retry(id: String) {
        val next = db.withTransaction {
            val row = dao.task(id) ?: return@withTransaction null
            require(row.status in listOf("FAILED", "UNKNOWN", "PENDING", "CANCELLED")) { "此任务不能重试" }
            require(dao.pendingCount() < 1000 || row.status == "PENDING") { "待发送队列已满，请先处理现有任务" }
            val pair = config(row.profileId)
            val previous = RuleJson.decodeFromString<RenderedRequest>(vault.decrypt(row.requestCipher))
            val snapshot = dao.notification(row.notificationId)?.let { RuleJson.decodeFromString<NotificationSnapshot>(it.snapshotJson) }
            val request = when {
                pair == null -> previous
                row.ruleId == "manual-test" -> {
                    val now = System.currentTimeMillis()
                    val sample = NotificationSnapshot("test", context.packageName, 0, "NotifyBox", "NotifyBox 测试", "这是一条测试消息。", now, now)
                    RequestRenderer.render(ProfileKind.valueOf(pair.first.kind), if (pair.first.kind == ProfileKind.TELEGRAM.name)
                        pair.second.copy(messageTemplate = "NotifyBox 测试消息") else pair.second, sample)
                }
                snapshot != null -> RequestRenderer.render(ProfileKind.valueOf(pair.first.kind), pair.second, snapshot)
                previous.telegram && pair.first.kind == ProfileKind.TELEGRAM.name -> {
                    // 原通知已过期时仍保留原消息，只替换当前机器人凭证和接收聊天。
                    val body = RuleJson.parseToJsonElement(previous.body).jsonObject.toMutableMap()
                    body["chat_id"] = JsonPrimitive(pair.second.chatId)
                    previous.copy(url = "https://api.telegram.org/bot" + pair.second.telegramToken + "/sendMessage", body = JsonObject(body).toString())
                }
                else -> previous
            }
            val value = row.copy(status = TaskStatus.PENDING.name, nextRunAt = System.currentTimeMillis(), httpCode = null,
                requestCipher = vault.encrypt(RuleJson.encodeToString(request)), summary = "等待手动重试")
            dao.updateTask(value); deliveryAction(value, value.status, value.summary); value
        }
        next?.let { QueueScheduler.schedule(context, it.id, it.nextRunAt) }
    }
    suspend fun pauseRetry(id: String) {
        db.withTransaction {
            val task = dao.task(id) ?: return@withTransaction
            require(task.status == "PENDING") { "发送已经开始，请等待本次结果" }
            val updated = task.copy(status = "CANCELLED", summary = "已暂停自动重试")
            dao.updateTask(updated); deliveryAction(updated, updated.status, updated.summary)
        }
    }
    suspend fun initialize() {
        val interrupted = dao.sending()
        interrupted.forEach {
            val outcome = DeliveryPolicy.retryable(DeliveryOutcome(TaskStatus.UNKNOWN, "上次发送中断，接收结果未知"), it.attempts, continuousRetry())
            val updated = it.copy(status = outcome.status.name, nextRunAt = System.currentTimeMillis() + (outcome.retryAfterSeconds ?: 0) * 1000, summary = outcome.summary)
            dao.updateTask(updated); deliveryAction(updated, updated.status, updated.summary)
        }
        while (true) {
            val batch = dao.unindexedNotifications()
            if (batch.isEmpty()) break
            batch.forEach { dao.updateNotification(indexed(it, RuleJson.decodeFromString(it.snapshotJson))) }
        }
        prune()
        dao.pending().forEach { QueueScheduler.schedule(context, it.id, it.nextRunAt) }
        QueueScheduler.scheduleMaintenance(context)
    }
    suspend fun prune() = db.withTransaction {
        val before = System.currentTimeMillis() - retentionDays() * 86_400_000L
        dao.pruneNotifications(before); dao.capNotifications()
        dao.pruneActions(before); dao.capActions(); dao.pruneTasks(before); dao.capTasks()
    }
    suspend fun deleteRecord(id: String) = db.withTransaction { dao.deleteActions(id); dao.deleteNotification(id) }
    suspend fun clearRecords() = db.withTransaction { dao.clearActions(); dao.clearNotifications() }
}
const val DEFAULT_THEME_RGB = 0x4868AE
