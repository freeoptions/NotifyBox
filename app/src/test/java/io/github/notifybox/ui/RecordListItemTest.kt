package io.github.notifybox.ui

import io.github.notifybox.core.NotificationSnapshot
import io.github.notifybox.core.RuleJson
import io.github.notifybox.data.NotificationRow
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RecordListItemTest {
    private fun row(id: String, text: String = id): NotificationRow {
        val snapshot = NotificationSnapshot(id, "sample.app", 0, "示例应用", "标题", text, 100, 200)
        return NotificationRow(id, id, 1, "ARRIVED", RuleJson.encodeToString(snapshot), "", 200)
    }

    @Test fun newPageDoesNotDependOnPreviousPageContents() {
        val previous = recordListItems(listOf(row("old")))
        val next = recordListItems(listOf(row("new")))
        // Lazy 布局可能仍持有上一页条目，两批数据均能独立渲染。
        assertEquals("new", next.single().snapshot!!.text)
        assertEquals("old", previous.single().snapshot!!.text)
        assertEquals(next.single().row.notificationKey, next.single().snapshot!!.key)
    }

    @Test fun updatingSameIdKeepsEachEmissionConsistent() {
        val previous = recordListItems(listOf(row("same", "旧内容")))
        val next = recordListItems(listOf(row("same", "新内容")))
        assertEquals("旧内容", previous.single().snapshot!!.text)
        assertEquals("新内容", next.single().snapshot!!.text)
    }

    @Test fun oneMalformedRecordDoesNotPreventOtherRecordsFromRendering() {
        val items = recordListItems(listOf(row("first"), row("broken").copy(snapshotJson = "{"), row("last")))
        assertEquals(3, items.size)
        assertEquals("first", items[0].snapshot!!.text)
        assertNull(items[1].snapshot)
        assertEquals("last", items[2].snapshot!!.text)
    }

    @Test fun emptyAndRestoredPagesCanBeReadIndependently() {
        val saved = recordListItems(listOf(row("saved")))
        assertTrue(recordListItems(emptyList()).isEmpty())
        assertEquals("saved", saved.single().snapshot!!.text)
        assertEquals("restored", recordListItems(listOf(row("restored"))).single().snapshot!!.text)
    }
}
