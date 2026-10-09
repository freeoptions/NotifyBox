package io.github.notifybox.core

import org.junit.Assert.*
import org.junit.Test

class DeliveryPolicyTest {
    @Test fun continuousRetryKeepsAmbiguousRequestInSameQueueUntilRecovery() {
        val timeout = DeliveryOutcome(TaskStatus.UNKNOWN, "连接超时")
        assertEquals(TaskStatus.PENDING, DeliveryPolicy.retryable(timeout, 100, true).status)
        assertEquals(1800L, DeliveryPolicy.retryable(timeout, 100, true).retryAfterSeconds)
        assertEquals(TaskStatus.UNKNOWN, DeliveryPolicy.retryable(timeout, 1, false).status)
    }
    @Test fun boundedRetryStopsAtThirdAttemptAndDoesNotRetryCredentialErrors() {
        val offline = DeliveryOutcome(TaskStatus.PENDING, "未连接", 60)
        assertEquals(TaskStatus.PENDING, DeliveryPolicy.retryable(offline, 2, false).status)
        assertEquals(TaskStatus.FAILED, DeliveryPolicy.retryable(offline, 3, false).status)
        assertEquals(TaskStatus.FAILED, DeliveryPolicy.retryable(DeliveryPolicy.response(401, true), 1, true).status)
        assertEquals(87L, DeliveryPolicy.retryable(DeliveryPolicy.response(429, true, false, 87), 1, true).retryAfterSeconds)
    }
    @Test fun genericSuccessUsesHttpStatus() { assertEquals(TaskStatus.SUCCESS, DeliveryPolicy.response(204, false).status) }
    @Test fun telegramNeedsBusinessSuccess() {
        assertEquals(TaskStatus.SUCCESS, DeliveryPolicy.response(200, true, true).status)
        assertEquals(TaskStatus.FAILED, DeliveryPolicy.response(200, true, false).status)
        assertEquals(TaskStatus.UNKNOWN, DeliveryPolicy.response(200, true, null).status)
    }
    @Test fun rateLimitHonorsServerDelay() {
        val r = DeliveryPolicy.response(429, true, false, 87)
        assertEquals(TaskStatus.PENDING, r.status); assertEquals(87L, r.retryAfterSeconds)
    }
    @Test fun authenticationFailureDoesNotRetry() { assertEquals(TaskStatus.FAILED, DeliveryPolicy.response(401, false).status) }
    @Test fun ambiguousServerFailureDoesNotAutomaticallyResend() { assertEquals(TaskStatus.UNKNOWN, DeliveryPolicy.response(503, false).status) }
    @Test fun redirectsAreNotSuccess() { assertEquals(TaskStatus.FAILED, DeliveryPolicy.response(302, false).status) }
}
