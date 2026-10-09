package io.github.notifybox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.notifybox.appGraph
import io.github.notifybox.core.*
import io.github.notifybox.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = app.appGraph
    val repository = graph.repository
    val rules = repository.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profiles = repository.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val records = repository.notifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val installedApps = MutableStateFlow<List<AppChoice>>(emptyList())
    val applicationsLoaded = MutableStateFlow(false)
    private var loadingApplications = false
    fun loadApplications() {
        if (applicationsLoaded.value || loadingApplications) return
        loadingApplications = true
        viewModelScope.launch {
            try {
                installedApps.value = withContext(Dispatchers.IO) { ApplicationCatalog.load(getApplication<Application>()) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                messages.emit("应用列表读取失败，可从通知记录选择或手动输入包名")
            } finally {
                loadingApplications = false
                applicationsLoaded.value = true
            }
        }
    }
    fun launch(success: String? = null, after: () -> Unit = {}, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.ready.await(); operation() }
                if (success != null) messages.emit(success)
                after()
            } catch (e: CancellationException) { throw e } catch (e: IllegalArgumentException) { messages.emit(e.message?.take(200) ?: "请检查填写内容") } catch (_: Exception) { messages.emit("操作失败，请检查配置和本地存储") }
        }
    }
}
