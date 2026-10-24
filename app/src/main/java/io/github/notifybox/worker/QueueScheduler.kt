package io.github.notifybox.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object QueueScheduler {
    fun schedule(context: Context, id: String, nextRunAt: Long) {
        val delayMs = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0)
        val builder = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setInputData(workDataOf("taskId" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        // 新消息立即转发走加急任务；有 Retry-After 的任务仍守住截止时间。
        if (delayMs == 0L) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        else builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        val request = builder.build()
        // 每个截止时间只建一个工作项，数据库 claim 防止并发发送。
        // 手动提前重试不等待旧延迟任务，也不累积无限依赖链。
        WorkManager.getInstance(context).enqueueUniqueWork("notifybox.send.$id.$nextRunAt", ExistingWorkPolicy.KEEP, request)
    }
    fun scheduleMaintenance(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("notifybox.retention", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build())
    }
}
