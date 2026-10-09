@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.notifybox.ui

import android.net.http.SslCertificate.restoreState
import android.net.http.SslCertificate.saveState
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import io.github.notifybox.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import io.github.notifybox.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

@Composable
fun NotifyBoxRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "rules"
    val tabs = listOf("rules" to "规则", "records" to "记录", "forwarding" to "转发", "settings" to "设置")
    val atRoot = tabs.any { it.first == route }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    fun tabTo(destination: String) {
        nav.navigate(destination) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    PredictiveBackHandler(enabled = atRoot && route != "rules" && !keyboard) { progress ->
        progress.collect { }
        tabTo("rules")
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (atRoot) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                tabs.forEach { (r, label) ->
                    NavigationBarItem(selected = route == r, onClick = { tabTo(r) },
                        icon = {
                            val icon = when (r) {
                                "rules" -> R.drawable.ic_rules
                                "records" -> R.drawable.ic_records
                                "forwarding" -> R.drawable.ic_forwarding
                                else -> R.drawable.ic_settings
                            }
                            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NotifyColors.blue,
                            selectedTextColor = NotifyColors.ink, indicatorColor = NotifyColors.blueTint,
                            unselectedIconColor = NotifyColors.muted, unselectedTextColor = NotifyColors.muted))
                }
            }
        },
    ) { padding ->
        // 顶级标签直接切换，避免两页同时淡入淡出产生文字重影；详情页保留过渡。
        NavHost(nav, startDestination = "rules",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().clipToBounds().background(NotifyColors.background),
            enterTransition = {
                if (tabs.any { it.first == initialState.destination.route } && tabs.any { it.first == targetState.destination.route }) EnterTransition.None
                else slideInHorizontally(tween(200)) { it }
            },
            exitTransition = {
                if (tabs.any { it.first == initialState.destination.route } && tabs.any { it.first == targetState.destination.route }) ExitTransition.None
                else slideOutHorizontally(tween(200)) { -it }
            },
            popEnterTransition = {
                if (tabs.any { it.first == initialState.destination.route } && tabs.any { it.first == targetState.destination.route }) EnterTransition.None
                else slideInHorizontally(tween(200)) { -it }
            },
            popExitTransition = {
                if (tabs.any { it.first == initialState.destination.route } && tabs.any { it.first == targetState.destination.route }) ExitTransition.None
                else slideOutHorizontally(tween(200)) { it }
            }) {
            composable("rules") { RulesScreen(vm, { nav.navigate("rule/new") }, { nav.navigate("rule/" + it) }, { nav.navigate("trial/" + it) }) }
            composable("records") { RecordsScreen(vm) { nav.navigate("record/" + it) } }
            composable("forwarding") { ForwardingScreen(vm, { nav.navigate("profile/new") }, { nav.navigate("profile/" + it) }) }
            composable("settings") { SettingsScreen(vm) }
            composable("rule/{id}") { back ->
                val id = back.arguments?.getString("id").orEmpty()
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                if (id == "new") {
                    val rule = remember { Rule(name = "", actions = listOf(DismissAction())) }
                    RuleEditor(vm, rule, profiles, { nav.popBackStack() }, isNew = true, openProfile = { nav.navigate("profile/" + it) })
                } else RuleLoader(vm, id) { rule -> RuleEditor(vm, rule, profiles, { nav.popBackStack() }, openProfile = { nav.navigate("profile/" + it) }) }
            }
            composable("from/{id}") { back ->
                val id = back.arguments?.getString("id").orEmpty()
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                RecordLoader(vm, id) { row ->
                    val n = remember(row) { RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson) }
                    val rule = remember(row) { Rule(name = n.appName + " 的规则", applications = listOf(ApplicationTarget(n.packageName, n.userId)), actions = listOf(DismissAction())) }
                    RuleEditor(vm, rule, profiles, { nav.popBackStack() }, isNew = true, openProfile = { nav.navigate("profile/" + it) })
                }
            }
            composable("record/{id}") { back ->
                val id = back.arguments?.getString("id").orEmpty()
                RecordLoader(vm, id) { row -> RecordDetail(vm, row, { nav.popBackStack() }, { nav.navigate("from/" + id) }) }
            }
            composable("profile/{id}") { back ->
                ProfileEditor(vm, back.arguments?.getString("id").orEmpty(), { nav.popBackStack() })
            }
            composable("trial/{id}") { back ->
                RuleLoader(vm, back.arguments?.getString("id").orEmpty()) { rule -> TrialScreen(vm, rule, { nav.popBackStack() }) }
            }
        }
    }
}
@Composable
private fun RuleLoader(vm: AppViewModel, id: String, content: @Composable (Rule) -> Unit) {
    var value by remember(id) { mutableStateOf<Rule?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        value = withContext(Dispatchers.IO) { vm.repository.getRule(id) }
        loaded = true
    }
    if (!loaded) CircularProgressIndicator(Modifier.padding(24.dp))
    else value?.let { content(it) } ?: Text("规则已不存在", Modifier.padding(24.dp))
}
@Composable
private fun RecordLoader(vm: AppViewModel, id: String, content: @Composable (io.github.notifybox.data.NotificationRow) -> Unit) {
    var value by remember(id) { mutableStateOf<io.github.notifybox.data.NotificationRow?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        value = withContext(Dispatchers.IO) { vm.repository.dao.notification(id) }
        loaded = true
    }
    if (!loaded) CircularProgressIndicator(Modifier.padding(24.dp))
    else value?.let { content(it) } ?: Text("记录已删除或超过保留时间", Modifier.padding(24.dp))
}
