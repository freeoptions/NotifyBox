@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.notifybox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.notifybox.R

@Composable
fun EditorSection(
    title: String, summary: String, initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White,
        border = BorderStroke(1.dp, NotifyColors.line), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开") {
                expanded = !expanded
            }.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = NotifyColors.blue, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                    if (!expanded && summary.isNotEmpty()) Text(summary, color = NotifyColors.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = if (expanded) "收起" else "展开", tint = NotifyColors.blue, modifier = Modifier.size(22.dp))
            }
            if (expanded) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
fun <T> ChoiceMenu(label: String, selected: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = NotifyColors.ink)
        Box(Modifier.widthIn(max = 220.dp)) {
            TextButton(onClick = { expanded = true }) {
                Text(choices.firstOrNull { it.first == selected }?.second ?: "请选择", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false },
                        leadingIcon = if (value == selected) { { Icon(painterResource(R.drawable.ic_check), null) } } else null)
                }
            }
        }
    }
}

@Composable
fun RemovableTag(label: String, onRemove: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(24.dp), color = NotifyColors.blueTint, modifier = Modifier.widthIn(max = 320.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
            Text(label, Modifier.weight(1f, fill = false), color = NotifyColors.ink, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_close), "移除“$label”", tint = NotifyColors.blue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun AddTag(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = NotifyColors.blueTint, contentColor = NotifyColors.blue)) {
        Text(label)
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp))
    }
}

@Composable
fun EditorFooter(saving: Boolean, onSave: () -> Unit, secondaryLabel: String, onSecondary: () -> Unit, destructive: Boolean = false) {
    Surface(color = Color.White, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !saving, onClick = onSecondary,
                colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else NotifyColors.muted)) {
                Text(secondaryLabel)
            }
            Spacer(Modifier.weight(1f))
            Button(enabled = !saving, onClick = onSave, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.widthIn(min = 148.dp).heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotifyColors.blue, contentColor = Color.White)) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (saving) "保存中…" else "保存", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
