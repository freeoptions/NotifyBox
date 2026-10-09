package io.github.notifybox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.util.UUID

val RuleJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }
fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class NotificationSnapshot(
    val key: String, val packageName: String, val userId: Int,
    val appName: String = packageName, val title: String = "", val text: String = "",
    val postedAt: Long, val receivedAt: Long, val ongoing: Boolean = false,
    val groupKey: String? = null, val groupSummary: Boolean = false,
) {
    fun sameContent(other: NotificationSnapshot) = packageName == other.packageName &&
        userId == other.userId && title == other.title && text == other.text &&
        ongoing == other.ongoing && groupKey == other.groupKey && groupSummary == other.groupSummary
}
@Serializable
data class ApplicationTarget(val packageName: String, val userId: Int? = null)
@Serializable enum class TextField { TITLE, TEXT }
@Serializable enum class TextOperator { CONTAINS, NOT_CONTAINS, ANY, ALL, REGEX }
@Serializable
data class TextCondition(
    val fields: Set<TextField> = setOf(TextField.TITLE, TextField.TEXT),
    val operator: TextOperator = TextOperator.ANY,
    val values: List<String> = emptyList(), val ignoreCase: Boolean = true,
)
@Serializable sealed interface RuleAction
@Serializable
@SerialName("dismiss")
data class DismissAction(val delayMs: Long = 5000, val includeOngoing: Boolean = true) : RuleAction
@Serializable
@SerialName("webhook")
data class WebhookAction(
    val profileId: String, val dedupEnabled: Boolean = true, val dedupWindowSeconds: Int = 5,
) : RuleAction
@Serializable
data class Rule(
    val id: String = newId(), val name: String, val enabled: Boolean = true,
    val schemaVersion: Int = 1, val applications: List<ApplicationTarget> = emptyList(),
    val textCondition: TextCondition = TextCondition(), val actions: List<RuleAction> = emptyList(),
    val migrationIssues: List<String> = emptyList(),
)
@Serializable enum class HttpMethod { GET, POST }
@Serializable enum class ProfileKind { GENERIC, TELEGRAM }
@Serializable
data class WebhookConfig(
    val method: HttpMethod = HttpMethod.POST, val urlTemplate: String = "",
    val headers: Map<String, String> = emptyMap(), val bodyTemplate: String = "",
    val jsonBody: Boolean = true, val telegramToken: String = "", val chatId: String = "",
    val messageTemplate: String = "{{android.title}}\n{{android.text}}",
)
@Serializable
data class ProfileMetadata(val id: String = newId(), val name: String, val kind: ProfileKind)
@Serializable
data class RuleBundle(
    val format: String = "notifybox", val schemaVersion: Int = 1,
    val rules: List<Rule>, val profiles: List<ProfileMetadata> = emptyList(),
    val profileConfigs: Map<String, WebhookConfig> = emptyMap(),
)
@Serializable enum class TaskStatus { PENDING, SENDING, SUCCESS, FAILED, UNKNOWN }
@Serializable enum class EventKind { ARRIVED, UPDATED, REMOVED }
data class ImportItem(val rule: Rule, val notes: List<String>, val blockers: List<String>)
data class ImportReport(val items: List<ImportItem>, val profiles: List<ProfileMetadata> = emptyList(), val notes: List<String> = emptyList(),
    val profileConfigs: Map<String, WebhookConfig> = emptyMap())
