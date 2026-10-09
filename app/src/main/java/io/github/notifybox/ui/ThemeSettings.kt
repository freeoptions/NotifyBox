@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.notifybox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.notifybox.data.DEFAULT_THEME_RGB
import java.util.Locale
import kotlin.math.roundToInt

private val themePresets = listOf(
    "雾蓝" to DEFAULT_THEME_RGB, "青绿" to 0x237D83, "松绿" to 0x3B7654,
    "紫罗兰" to 0x7656A6, "玫瑰" to 0xA65077, "暖橙" to 0xA46832,
)
private fun hexColor(rgb: Int) = "#%06X".format(Locale.ROOT, rgb)
private fun parseThemeColor(value: String): Int? = value.removePrefix("#")
    .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toIntOrNull(16)

@Composable
fun ThemeSettings(vm: AppViewModel) {
    val selected by vm.repository.themeColor.collectAsStateWithLifecycle()
    var custom by rememberSaveable { mutableStateOf(false) }
    SectionCard("主题色") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            themePresets.forEach { (name, rgb) ->
                val swatch = remember(rgb) { themeAccentColor(rgb) }
                FilterChip(selected = selected == rgb, onClick = { vm.repository.setThemeColor(rgb) },
                    label = { Text(name) }, leadingIcon = {
                        Box(Modifier.size(18.dp).background(swatch, CircleShape))
                    })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(themePresets.firstOrNull { it.second == selected }?.first ?: "自定义 ${hexColor(selected)}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { custom = true }) { Text("自定义") }
            TextButton(enabled = selected != DEFAULT_THEME_RGB, onClick = { vm.repository.setThemeColor(DEFAULT_THEME_RGB) }) { Text("恢复默认") }
        }
    }
    if (custom) CustomThemeDialog(selected, onDismiss = { custom = false }, onApply = {
        vm.repository.setThemeColor(it); custom = false
    })
}

@Preview(showBackground = true, widthDp = 393, heightDp = 900, name = "主题色对比")
@Composable
private fun ThemeComparisonPreview() {
    Column {
        listOf(themePresets[0], themePresets[1], themePresets[4]).forEach { (name, rgb) ->
            NotifyBoxTheme(accentRgb = rgb) {
                Column(Modifier.fillMaxWidth().background(NotifyColors.background).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PageIntro(name, "")
                    SectionCard("短信通知") {
                        Text("5 秒后消除", color = NotifyColors.inkSoft)
                        ToggleRow("启用规则", true, {})
                    }
                    PrimaryAction("保存规则", {})
                }
            }
        }
    }
}

@Composable
private fun CustomThemeDialog(initial: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var rgb by rememberSaveable { mutableIntStateOf(initial) }
    var hex by rememberSaveable { mutableStateOf(hexColor(initial)) }
    val valid = parseThemeColor(hex)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义主题色") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 局部预览不会改变页面主题；应用后全局生效，取消保留原选择。
                NotifyBoxTheme(accentRgb = rgb) {
                    Surface(color = NotifyColors.background, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("预览效果", color = NotifyColors.ink)
                            Surface(color = NotifyColors.blue, shape = MaterialTheme.shapes.small) {
                                Text("主题色", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White)
                            }
                        }
                    }
                }
                OutlinedTextField(hex, { value ->
                    hex = value.take(7).uppercase(Locale.ROOT)
                    parseThemeColor(hex)?.let { rgb = it }
                }, label = { Text("色值（#RRGGBB）") }, singleLine = true, isError = valid == null,
                    supportingText = { Text(if (valid == null) "请输入六位色值，例如 #4868AE" else "浅色会自动加深，保证文字清晰") },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                listOf("红" to 16, "绿" to 8, "蓝" to 0).forEach { (name, shift) ->
                    val value = (rgb shr shift) and 0xFF
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.width(24.dp))
                        Slider(value = value.toFloat(), valueRange = 0f..255f, onValueChange = {
                            rgb = (rgb and (0xFF shl shift).inv()) or (it.roundToInt() shl shift)
                            hex = hexColor(rgb)
                        }, modifier = Modifier.weight(1f))
                        Text(value.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid != null, onClick = { valid?.let(onApply) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
