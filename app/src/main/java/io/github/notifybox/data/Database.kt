package io.github.notifybox.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleRow(@PrimaryKey val id: String, val json: String, val createdAt: Long)
@Entity(tableName = "profiles")
data class ProfileRow(@PrimaryKey val id: String, val name: String, val kind: String, val configCipher: String = "")
@Entity(tableName = "notifications", indices = [Index("createdAt"), Index("notificationKey")])
data class NotificationRow(
    @PrimaryKey val id: String, val notificationKey: String, val revision: Long,
    val kind: String, val snapshotJson: String, val matchedNames: String, val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val titleSearch: String = "",
    @ColumnInfo(defaultValue = "''") val textSearch: String = "",
    @ColumnInfo(defaultValue = "''") val appSearch: String = "",
    @ColumnInfo(defaultValue = "''") val ruleSearch: String = "",
)
@Entity(tableName = "dismiss_plans", primaryKeys = ["notificationKey", "ruleId"])
data class DismissRow(val notificationKey: String, val ruleId: String, val revision: Long,
    val snapshotJson: String, val recordId: String, val ruleName: String, val dueAt: Long)
@Entity(tableName = "actions", indices = [Index("notificationId"), Index("createdAt")])
data class ActionRow(
    @PrimaryKey val id: String, val notificationId: String, val ruleName: String,
    val action: String, val status: String, val summary: String, val createdAt: Long,
)
@Entity(tableName = "tasks", indices = [Index("status"), Index("dedupKey"), Index("createdAt"), Index("notificationId")])
data class TaskRow(
    @PrimaryKey val id: String, val notificationId: String, val ruleId: String, val ruleName: String,
    val profileId: String, val profileName: String, val packageName: String, val userId: Int,
    val requestCipher: String, val dedupKey: String, val status: String = "PENDING",
    val createdAt: Long, val nextRunAt: Long, val attempts: Int = 0, val httpCode: Int? = null,
    val summary: String = "等待网络发送",
    @ColumnInfo(defaultValue = "0") val lastAttemptAt: Long = 0,
)

@Dao
interface NotifyDao {
    @Query("SELECT * FROM rules ORDER BY createdAt") fun observeRules(): Flow<List<RuleRow>>
    @Query("SELECT * FROM rules ORDER BY createdAt") suspend fun rules(): List<RuleRow>
    @Query("SELECT * FROM rules WHERE id=:id") suspend fun rule(id: String): RuleRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(row: RuleRow)
    @Query("DELETE FROM rules WHERE id=:id") suspend fun deleteRule(id: String)
    @Query("SELECT * FROM profiles ORDER BY name") fun observeProfiles(): Flow<List<ProfileRow>>
    @Query("SELECT * FROM profiles ORDER BY name") suspend fun profiles(): List<ProfileRow>
    @Query("SELECT * FROM profiles WHERE id=:id") suspend fun profile(id: String): ProfileRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfile(row: ProfileRow)
    @Query("SELECT * FROM notifications WHERE kind!='REMOVED' ORDER BY createdAt DESC LIMIT 500") fun observeNotifications(): Flow<List<NotificationRow>>
    @Query("SELECT * FROM notifications WHERE id=:id") suspend fun notification(id: String): NotificationRow?
    @Query("SELECT * FROM notifications WHERE notificationKey=:key ORDER BY createdAt DESC, rowid DESC LIMIT 1") suspend fun latestNotification(key: String): NotificationRow?
    @Query("SELECT EXISTS(SELECT 1 FROM actions WHERE id='removed.' || :id)") suspend fun notificationWasRemoved(id: String): Boolean
    @RawQuery(observedEntities = [NotificationRow::class, ActionRow::class]) fun notificationPage(query: SupportSQLiteQuery): Flow<List<NotificationRow>>
    @RawQuery(observedEntities = [NotificationRow::class, ActionRow::class]) fun notificationCount(query: SupportSQLiteQuery): Flow<Int>
    @Query("SELECT * FROM notifications WHERE appSearch='' LIMIT 100") suspend fun unindexedNotifications(): List<NotificationRow>
    @Update suspend fun updateNotification(row: NotificationRow)
    @Insert suspend fun addNotification(row: NotificationRow)
    @Query("DELETE FROM notifications WHERE id=:id") suspend fun deleteNotification(id: String)
    @Query("DELETE FROM actions WHERE notificationId=:id") suspend fun deleteActions(id: String)
    @Query("DELETE FROM notifications") suspend fun clearNotifications()
    @Query("DELETE FROM actions") suspend fun clearActions()
    @Query("SELECT * FROM actions WHERE notificationId=:id ORDER BY createdAt") fun observeActions(id: String): Flow<List<ActionRow>>
    @Query("SELECT * FROM actions WHERE notificationId IN (SELECT id FROM notifications ORDER BY createdAt DESC LIMIT 500) ORDER BY createdAt DESC") fun observeAllActions(): Flow<List<ActionRow>>
    @Insert suspend fun addAction(row: ActionRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAction(row: ActionRow)
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT 500") fun observeTasks(): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE (:status='' OR status=:status) ORDER BY createdAt DESC, id DESC LIMIT 20 OFFSET :offset") fun taskPage(status: String, offset: Int): Flow<List<TaskRow>>
    @Query("SELECT COUNT(*) FROM tasks WHERE (:status='' OR status=:status)") fun taskCount(status: String): Flow<Int>
    @Query("SELECT * FROM actions WHERE notificationId IN (:ids) ORDER BY createdAt DESC, rowid DESC") fun pageActions(ids: List<String>): Flow<List<ActionRow>>
    @Query("SELECT * FROM tasks WHERE notificationId=:id ORDER BY createdAt") fun observeTasksForNotification(id: String): Flow<List<TaskRow>>
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskRow?
    @Insert suspend fun addTask(row: TaskRow)
    @Update suspend fun updateTask(row: TaskRow)
    @Query("SELECT COUNT(*) FROM tasks WHERE status IN ('PENDING', 'SENDING')") suspend fun pendingCount(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM tasks WHERE dedupKey=:key AND createdAt>=:since)") suspend fun duplicate(key: String, since: Long): Boolean
    @Query("UPDATE tasks SET status='SENDING', attempts=attempts+1, lastAttemptAt=:now, summary='正在发送' WHERE id=:id AND status='PENDING'")
    suspend fun claim(id: String, now: Long): Int
    @Query("SELECT * FROM tasks WHERE status='PENDING'") suspend fun pending(): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE status='SENDING'") suspend fun sending(): List<TaskRow>
    @Query("UPDATE tasks SET status='UNKNOWN', summary='上次发送中断，接收结果未知；可手动重试' WHERE status='SENDING'") suspend fun recoverInterrupted()
    @Query("DELETE FROM notifications WHERE createdAt<:before") suspend fun pruneNotifications(before: Long)
    @Query("DELETE FROM notifications WHERE id IN (SELECT id FROM notifications ORDER BY createdAt DESC LIMIT -1 OFFSET 10000)") suspend fun capNotifications()
    @Query("DELETE FROM actions WHERE createdAt<:before OR notificationId NOT IN (SELECT id FROM notifications)") suspend fun pruneActions(before: Long)
    @Query("DELETE FROM actions WHERE id IN (SELECT id FROM actions ORDER BY createdAt DESC LIMIT -1 OFFSET 50000)") suspend fun capActions()
    @Query("DELETE FROM tasks WHERE createdAt<:before AND status NOT IN ('PENDING','SENDING')") suspend fun pruneTasks(before: Long)
    @Query("DELETE FROM tasks WHERE id IN (SELECT id FROM tasks WHERE status NOT IN ('PENDING','SENDING') ORDER BY createdAt DESC LIMIT -1 OFFSET 10000)") suspend fun capTasks()
    @Query("SELECT * FROM dismiss_plans") suspend fun dismissPlans(): List<DismissRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDismissPlan(row: DismissRow)
    @Query("DELETE FROM dismiss_plans WHERE notificationKey=:key") suspend fun deleteDismissPlans(key: String)
    @Query("DELETE FROM dismiss_plans WHERE notificationKey=:key AND ruleId=:ruleId") suspend fun deleteDismissPlan(key: String, ruleId: String)
}

@Database(entities = [RuleRow::class, ProfileRow::class, NotificationRow::class, ActionRow::class, TaskRow::class, DismissRow::class], version = 2, exportSchema = true)
abstract class NotifyDatabase : RoomDatabase() {
    abstract fun dao(): NotifyDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS dismiss_plans (notificationKey TEXT NOT NULL, ruleId TEXT NOT NULL, revision INTEGER NOT NULL, snapshotJson TEXT NOT NULL, recordId TEXT NOT NULL, ruleName TEXT NOT NULL, dueAt INTEGER NOT NULL, PRIMARY KEY(notificationKey, ruleId))")
                listOf("titleSearch", "textSearch", "appSearch", "ruleSearch").forEach {
                    db.execSQL("ALTER TABLE notifications ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL("ALTER TABLE tasks ADD COLUMN lastAttemptAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
