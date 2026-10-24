package io.github.notifybox.ui

import io.github.notifybox.core.ApplicationTarget
import org.junit.Assert.*
import org.junit.Test

class ApplicationChoicesTest {
    private val main = listOf(
        AppChoice("com.tencent.mm", "微信", 0, AppChoiceSource.INSTALLED),
        AppChoice("com.tencent.wework", "企业微信", 0, AppChoiceSource.INSTALLED),
        AppChoice("com.tencent.wetype", "微信输入法", 0, AppChoiceSource.INSTALLED),
    )
    private val clone = AppChoice("com.tencent.mm", "微信", 999, AppChoiceSource.NOTIFICATION)

    @Test fun userIdComesFromFullUidRatherThanAppIdOrSerialNumber() {
        assertEquals(0, androidUserIdFromUid(10123))
        assertEquals(10, androidUserIdFromUid(1010123))
        assertEquals(999, androidUserIdFromUid(99910123))
        assertNull(androidUserIdFromUid(-1))
    }

    @Test fun specifiedUserContainsOnlyKnownInstances() {
        val choices = mergeApplicationChoices(main, listOf(clone), emptyList())
        val filtered = applicationChoicesForScope(choices, "custom", 999)
        assertEquals(listOf(ApplicationTarget("com.tencent.mm", 999)), filtered.map { it.target })
        assertEquals("微信（多开）", applicationChoiceLabel(filtered.single()))
        assertEquals("微信（多开）", targetCaption(filtered.single().target, choices))
    }

    @Test fun automaticModeKeepsMainAndCloneSeparate() {
        val choices = mergeApplicationChoices(main, listOf(clone), emptyList())
        val wechat = applicationChoicesForScope(choices, "instances", null).filter { it.packageName == "com.tencent.mm" }
        assertEquals(listOf(0, 999), wechat.map { it.userId })
        assertFalse(wechat.first().multiOpen)
        assertEquals(setOf(ApplicationTarget("com.tencent.mm", 0), ApplicationTarget("com.tencent.mm", 999)),
            wechat.map { it.target }.toSet())
    }

    @Test fun mainOnlyDoesNotContainClone() {
        val choices = mergeApplicationChoices(main, listOf(clone), emptyList())
        assertEquals(main.map { it.target }.toSet(), applicationChoicesForScope(choices, "main", 0).map { it.target }.toSet())
    }

    @Test fun wildcardRemainsExplicitAndDoesNotDuplicatePackages() {
        val choices = mergeApplicationChoices(main, listOf(clone), emptyList())
        val all = applicationChoicesForScope(choices, "all", null)
        assertEquals(3, all.size)
        assertTrue(all.all { it.target.userId == null && !it.multiOpen })
        assertEquals("企业微信", targetCaption(ApplicationTarget("com.tencent.wework"), choices))
    }

    @Test fun configuredUserIsNotProofOfInstallation() {
        val choices = mergeApplicationChoices(main, emptyList(), listOf(ApplicationTarget("com.tencent.wework", 999)))
        assertTrue(applicationChoicesForScope(choices, "custom", 999).isEmpty())
        assertEquals("企业微信", targetCaption(ApplicationTarget("com.tencent.wework", 999), choices))
    }

    @Test fun arbitraryUserNumberDoesNotImplyClone() {
        val choices = mergeApplicationChoices(emptyList(), listOf(clone), emptyList())
        assertFalse(choices.single().multiOpen)
        assertEquals("微信", applicationChoiceLabel(choices.single()))
    }

    @Test fun systemCloneMetadataDoesNotRequireMainAppVisibility() {
        val installed = listOf(clone.copy(source = AppChoiceSource.INSTALLED, userId = 12, profileKind = AppProfileKind.CLONE))
        val choices = mergeApplicationChoices(installed, emptyList(), emptyList())
        assertEquals("微信（多开）", applicationChoiceLabel(choices.single()))
    }

    @Test fun workProfileRemainsDistinctFromClone() {
        val work = clone.copy(source = AppChoiceSource.INSTALLED, userId = 10, profileKind = AppProfileKind.WORK)
        val choices = mergeApplicationChoices(main, listOf(work), emptyList())
        val filtered = applicationChoicesForScope(choices, "custom", 10)
        assertFalse(filtered.single().multiOpen)
        assertEquals("微信（工作）", applicationChoiceLabel(filtered.single()))
    }

    @Test fun installedMetadataTakesPrecedenceOverHistoricalRecord() {
        val installed = clone.copy(source = AppChoiceSource.INSTALLED, name = "微信", profileKind = AppProfileKind.CLONE)
        val choices = mergeApplicationChoices(main + installed, listOf(clone.copy(name = "旧名称"), clone), emptyList())
        val instance = choices.single { it.target == clone.target }
        assertEquals(AppChoiceSource.INSTALLED, instance.source)
        assertEquals(AppProfileKind.CLONE, instance.profileKind)
        assertEquals("微信（多开）", applicationChoiceLabel(instance))
    }

    @Test fun invalidOrUnknownUsersNeverFallBackToMainList() {
        val choices = mergeApplicationChoices(main, listOf(clone.copy(userId = -1)), emptyList())
        assertTrue(applicationChoicesForScope(choices, "custom", null).isEmpty())
        assertTrue(applicationChoicesForScope(choices, "custom", -1).isEmpty())
        assertTrue(applicationChoicesForScope(choices, "custom", 123).isEmpty())
    }
}
