package io.github.notifybox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.ui.NotifyBoxRoot
import io.github.notifybox.ui.NotifyBoxTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        io.github.notifybox.listener.BackgroundService.start(this)
        if (!io.github.notifybox.listener.ListenerState.connected.value)
            io.github.notifybox.listener.BackgroundService.requestListener(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeColor by appGraph.repository.themeColor.collectAsStateWithLifecycle()
            NotifyBoxTheme(accentRgb = themeColor) { NotifyBoxRoot() }
        }
    }
}
