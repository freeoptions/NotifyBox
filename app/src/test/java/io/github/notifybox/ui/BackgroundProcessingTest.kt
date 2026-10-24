package io.github.notifybox.ui

import io.github.notifybox.listener.QueuedWakeGuard
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BackgroundProcessingTest {
    private class Lock {
        var held = false
        var acquisitions = 0
        var releases = 0
        val guard = QueuedWakeGuard(
            acquire = { held = true; acquisitions++ },
            release = { held = false; releases++ },
        )
    }

    @Test fun queuedEventKeepsProtectionWhileProcessorIsIdleOrInitializing() {
        val lock = Lock()
        lock.guard.enqueue()
        // 定时等待锁可以独立释放，不影响通知队列锁。
        assertTrue(lock.held)
        assertEquals(0, lock.releases)
        lock.guard.complete()
        assertFalse(lock.held)
    }

    @Test fun arrivingEventCannotLoseWakeProtectionWhenPreviousEventCompletes() {
        val lock = Lock()
        lock.guard.enqueue()
        lock.guard.enqueue() // 旧事件结束前，主线程又交付一条通知。
        lock.guard.complete()
        assertTrue(lock.held)
        assertEquals(0, lock.releases)
        lock.guard.complete()
        assertFalse(lock.held)
        assertEquals(1, lock.releases)
    }

    @Test fun rejectedEventReleasesOnlyItsOwnQueueReservation() {
        val lock = Lock()
        lock.guard.enqueue()
        lock.guard.enqueue()
        lock.guard.complete() // trySend 失败的回滚。
        assertTrue(lock.held)
        lock.guard.complete()
        assertFalse(lock.held)
    }

    @Test fun newEventRefreshesExpiredWakeTimeoutWithoutLosingQueuedCounts() {
        val lock = Lock()
        lock.guard.enqueue()
        lock.held = false // 模拟初始化阻塞超过有界超时，系统释放实际锁。
        lock.guard.enqueue()
        assertTrue(lock.held)
        assertEquals(2, lock.acquisitions)
        lock.guard.complete()
        assertTrue(lock.held)
        lock.guard.complete()
        assertFalse(lock.held)
    }

    @Test fun failedAcquisitionDoesNotLeavePhantomQueuedEvent() {
        var fail = true
        var released = false
        val guard = QueuedWakeGuard({ if (fail) throw IllegalStateException("unavailable") }, { released = true })
        assertThrows(IllegalStateException::class.java) { guard.enqueue() }
        fail = false
        guard.enqueue()
        guard.complete()
        assertTrue(released)
    }

    @Test fun concurrentCallbacksCannotReleaseProtectionOfRemainingEvents() {
        val lock = Lock()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val failure = AtomicReference<Throwable?>(null)
        lock.guard.enqueue() // 保留一条尚未处理的通知。
        try {
            repeat(4) {
                pool.execute {
                    try {
                        start.await()
                        repeat(100) { lock.guard.enqueue(); lock.guard.complete() }
                    } catch (e: Throwable) { failure.set(e) }
                    finally { done.countDown() }
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertTrue(lock.held)
            assertEquals(0, lock.releases)
            lock.guard.complete()
            assertFalse(lock.held)
        } finally { pool.shutdownNow() }
    }
}
