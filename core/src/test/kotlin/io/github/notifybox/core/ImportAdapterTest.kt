package io.github.notifybox.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ImportAdapterTest {
    @Test fun fullExportPreservesCredentialsAndImportsThemWithMappedMetadata() {
        val profile = ProfileMetadata("profile", "短信转发", ProfileKind.TELEGRAM)
        val config = WebhookConfig(telegramToken = "123:EXAMPLE_TOKEN", chatId = "-12345")
        val rule = Rule(name = "短信", enabled = true, actions = listOf(WebhookAction(profile.id)))
        val source = RuleJson.encodeToString(RuleBundle(schemaVersion = 2, rules = listOf(rule), profiles = listOf(profile), profileConfigs = mapOf(profile.id to config)))
        val report = ImportAdapter.preview(source)
        assertEquals(config, report.profileConfigs[profile.id])
        assertFalse(report.items.single().rule.enabled)
        assertTrue(report.items.single().blockers.isEmpty())
        assertFalse(report.items.single().notes.any { it.contains("补填") })
    }
    @Test fun allDismissIgnoresResidualTextAndDropsButtonActions() {
        val r = ImportAdapter.preview("""[{"name":"全部消除","applications":[{"packageName":"com.example","userId":0}],"strs1":["旧关键词"],"actions":[5,9]}]""").items.single()
        assertTrue(r.rule.textCondition.values.isEmpty())
        assertEquals(DismissAction(4000, true), r.rule.actions.single())
        assertFalse(r.rule.enabled); assertTrue(r.blockers.isNotEmpty())
        assertTrue(r.notes.any { it.contains("通知按钮") })
    }
    @Test fun partialDismissRequiresFieldConfirmation() {
        val r = ImportAdapter.preview("""[{"name":"部分消除","applications":[{"packageName":"android","userId":0}],"strs1":["关键词"]}]""").items.single()
        assertTrue(r.blockers.any { it.contains("strs1") })
        assertFalse(RuleEngine().matches(r.rule.copy(enabled = true), NotificationSnapshot("k", "android", 0, postedAt = 0, receivedAt = 0)))
    }
    @Test fun telegramIsRebuiltWithoutCopyingCredential() {
        val input = """[{"name":"微信转发至 tg","applications":[{"packageName":"com.tencent.mm","userId":999}],"url":"https://example.org/PLACEHOLDER_CREDENTIAL","method":1,"distinct":true}]"""
        val report = ImportAdapter.preview(input)
        assertEquals(ProfileKind.TELEGRAM, report.profiles.single().kind)
        assertTrue(report.items.single().rule.actions.single() is WebhookAction)
        assertFalse(RuleJson.encodeToString(report.items.single().rule).contains("PLACEHOLDER_CREDENTIAL"))
        assertTrue(report.items.single().blockers.any { it.contains("内部枚举") })
    }
    @Test fun smsHasConfirmedExclusion() {
        val r = ImportAdapter.preview("""[{"name":"短信转发至 tg","applications":[{"packageName":"com.android.mms","userId":0}]}]""").items.single().rule
        assertEquals(TextOperator.NOT_CONTAINS, r.textCondition.operator)
        assertEquals(listOf("点按即可了解详情或停止应用"), r.textCondition.values)
    }
    @Test fun nativeExportRemainsDisabledOnImport() {
        val rule = Rule(name = "通知", enabled = true, actions = listOf(DismissAction()))
        val report = ImportAdapter.preview(RuleJson.encodeToString(RuleBundle(rules = listOf(rule))))
        assertFalse(report.items.single().rule.enabled)
        assertTrue(report.items.single().blockers.isEmpty())
    }
    @Test fun unknownRestrictionsAndUrlNewlinesAreReported() {
        val r = ImportAdapter.preview("""[{"name":"全部消除","applications":[{"packageName":"com.example"}],"weekday":3,"charging":1,"url":"https://example.org/\n"}]""").items.single()
        assertTrue(r.blockers.any { it.contains("weekday") && it.contains("charging") })
        assertTrue(r.notes.any { it.contains("换行") })
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateNativeProfileIdsAreRejected() {
        val p = ProfileMetadata("p", "配置", ProfileKind.GENERIC)
        ImportAdapter.preview(RuleJson.encodeToString(RuleBundle(rules = emptyList(), profiles = listOf(p, p))))
    }
    @Test(expected = IllegalArgumentException::class) fun missingNativeProfileCannotBindToExistingDeviceConfig() {
        val r = Rule(name = "转发", actions = listOf(WebhookAction("foreign")))
        ImportAdapter.preview(RuleJson.encodeToString(RuleBundle(rules = listOf(r))))
    }
}
