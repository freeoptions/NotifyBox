package io.github.notifybox.ui

import io.github.notifybox.core.NotificationSnapshot
import io.github.notifybox.core.RuleJson
import io.github.notifybox.data.NotificationRow
import kotlinx.serialization.decodeFromString

data class RecordListItem(val row: NotificationRow, val snapshot: NotificationSnapshot?)

fun recordListItems(rows: List<NotificationRow>): List<RecordListItem> = rows.map { row ->
    val snapshot = try {
        RuleJson.decodeFromString<NotificationSnapshot>(row.snapshotJson)
    } catch (_: IllegalArgumentException) {
        null
    }
    RecordListItem(row, snapshot)
}
