package io.github.notifybox.listener

/** 队列与定时等待使用不同的锁，避免处理器准备休眠时释放刚入队事件的保护。 */
class QueuedWakeGuard(private val acquire: () -> Unit, private val release: () -> Unit) {
    private var pending = 0

    @Synchronized fun enqueue() {
        acquire() // 实际唤醒锁必须有超时；后续事件会刷新超时。
        pending++
    }

    @Synchronized fun complete() {
        check(pending > 0)
        pending--
        if (pending == 0) release()
    }
}
