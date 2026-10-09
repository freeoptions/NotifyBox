package io.github.notifybox

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.notifybox.data.*
import io.github.notifybox.listener.NotificationProcessor
import kotlinx.coroutines.*

class NotifyBoxApp : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = Room.databaseBuilder(context, NotifyDatabase::class.java, "notifybox.db")
        .addMigrations(NotifyDatabase.MIGRATION_1_2).build()
    val repository = Repository(database, SecretVault(), context)
    val ready = CompletableDeferred<Unit>()
    val processor = NotificationProcessor(context, repository, scope, ready)
    init {
        scope.launch {
            try { repository.initialize(); ready.complete(Unit) } catch (e: Exception) { ready.completeExceptionally(e) }
        }
    }
}
val Context.appGraph: AppGraph get() = (applicationContext as NotifyBoxApp).graph
