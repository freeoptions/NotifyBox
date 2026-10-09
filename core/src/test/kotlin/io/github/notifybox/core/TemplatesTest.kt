package io.github.notifybox.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TemplatesTest {
    private val n = NotificationSnapshot("k", "com.example", 0, "应用", "中文 \"引号\"\n标题", "正文 & # + ?", 10, 20)
    @Test fun jsonEscapesQuotesAndNewlineWithoutChangingContent() {
        val output = Templates.json("""{"title":"{{android.title}}","text":"{{android.text}}","nested":["{{android.appName}}",3]}""", n)
        val value = RuleJson.parseToJsonElement(output).jsonObject
        assertEquals(n.title, value.getValue("title").jsonPrimitive.content)
        assertEquals(n.text, value.getValue("text").jsonPrimitive.content)
        assertEquals(3, value.getValue("nested").jsonArray[1].jsonPrimitive.int)
    }
    @Test fun urlPlaceholderIsPercentEncoded() {
        val result = Templates.url("https://example.org/?text={{android.text}}", n)
        assertTrue(result.contains("%26")); assertTrue(result.contains("%23")); assertTrue(result.contains("%2B"))
        assertFalse(result.contains("正文")); assertFalse(result.contains(" "))
    }
    @Test fun oldSingleBraceAliasesWork() { assertEquals(n.packageName, Templates.plain("{filterbox.field.PACKAGE_NAME}", n)) }
    @Test(expected = IllegalStateException::class) fun unverifiedWhenAliasFails() { Templates.plain("{filterbox.field.WHEN}", n) }
    @Test(expected = IllegalArgumentException::class) fun cleartextUrlIsRejected() { Templates.url("http://example.org/", n) }
    @Test fun telegramBodyHasOnlyExpectedShape() {
        val request = RequestRenderer.render(ProfileKind.TELEGRAM, WebhookConfig(telegramToken = "123:TEST_PLACEHOLDER", chatId = "456"), n)
        val body = RuleJson.parseToJsonElement(request.body).jsonObject
        assertEquals(setOf("chat_id", "text"), body.keys)
        assertEquals(n.title + "\n" + n.text, body.getValue("text").jsonPrimitive.content)
        assertEquals(HttpMethod.POST, request.method)
    }
    @Test fun dedupSeparatesUsersRulesAndTargets() {
        val request = RenderedRequest(HttpMethod.GET, "https://example.org/", emptyMap(), "", true, false)
        val key = RequestRenderer.dedupKey("r1", "p1", n, request)
        assertNotEquals(key, RequestRenderer.dedupKey("r1", "p1", n.copy(userId = 999), request))
        assertNotEquals(key, RequestRenderer.dedupKey("r2", "p1", n, request))
        assertNotEquals(key, RequestRenderer.dedupKey("r1", "p2", n, request))
        assertEquals(key, RequestRenderer.dedupKey("r1", "p1", n.copy(key = "other", postedAt = 300), request))
    }
    @Test fun renderedHeaderCannotInjectLines() {
        val c = WebhookConfig(method = HttpMethod.GET, urlTemplate = "https://example.org", headers = mapOf("X-Title" to "{{android.title}}"))
        assertTrue(runCatching { RequestRenderer.render(ProfileKind.GENERIC, c, n) }.isFailure)
    }
}
