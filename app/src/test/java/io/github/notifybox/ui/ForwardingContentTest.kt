package io.github.notifybox.ui

import io.github.notifybox.core.*
import org.junit.Assert.*
import org.junit.Test

class ForwardingContentTest {
    private val notification = NotificationSnapshot("sample", "sample.app", 0, "示例应用",
        "新订单", "中文、\"引号\"与\n换行 😀", 100, 200)

    @Test fun telegramDisplaysRenderedMessageWithoutEnvelopeOrCredentials() {
        val request = RequestRenderer.render(ProfileKind.TELEGRAM,
            WebhookConfig(telegramToken = "123:placeholder", chatId = "sample-chat",
                messageTemplate = "收到：{{android.title}}\n{{android.text}}"), notification)
        val text = forwardingMessage(request)
        assertEquals("收到：新订单\n中文、\"引号\"与\n换行 😀", text)
        assertFalse(text.contains("placeholder"))
        assertFalse(text.contains("sample-chat"))
        assertFalse(text.contains("chat_id"))
    }

    @Test fun fixedTestMessageDisplaysActualRequestNotOriginalNotification() {
        val request = RequestRenderer.render(ProfileKind.TELEGRAM,
            WebhookConfig(telegramToken = "123:placeholder", chatId = "sample-chat",
                messageTemplate = "NotifyBox 测试消息"), notification)
        assertEquals("NotifyBox 测试消息", forwardingMessage(request))
    }

    @Test fun webhookDisplaysExactBody() {
        val body = "{\"message\":\"示例内容\",\"quantity\":2}"
        val request = RenderedRequest(HttpMethod.POST, "https://example.invalid/", emptyMap(), body, true, false)
        assertEquals(body, forwardingMessage(request))
    }

    @Test fun getWithoutBodyDoesNotExposeUrlOrHeaders() {
        val request = RenderedRequest(HttpMethod.GET, "https://example.invalid/?key=placeholder",
            mapOf("Authorization" to "placeholder"), "", false, false)
        assertEquals("（此请求没有正文）", forwardingMessage(request))
    }

    @Test fun malformedTelegramContentIsNotDisplayedAsRawRequest() {
        val request = RenderedRequest(HttpMethod.POST, "https://example.invalid/", emptyMap(),
            "{\"chat_id\":\"sample-chat\"}", true, true)
        assertThrows(IllegalArgumentException::class.java) { forwardingMessage(request) }
        assertThrows(IllegalArgumentException::class.java) { forwardingMessage(request.copy(body = "{")) }
    }
}
