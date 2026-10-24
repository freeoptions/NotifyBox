package io.github.notifybox.ui

import io.github.notifybox.core.ApplicationTarget

enum class AppChoiceSource { INSTALLED, NOTIFICATION, CONFIGURED }
enum class AppProfileKind { UNKNOWN, CLONE, WORK, PRIVATE }

// Android 的 UID 按每用户 100000 段编码；这里只转换已取得的应用 UID，不推测用户。
// AOSP: frameworks/base/core/java/android/os/UserHandle.java (getUserId / PER_USER_RANGE)
fun androidUserIdFromUid(uid: Int): Int? = if (uid < 0) null else uid / 100_000

data class AppChoice(
    val packageName: String,
    val name: String,
    val userId: Int? = null,
    val source: AppChoiceSource = AppChoiceSource.CONFIGURED,
    val profileKind: AppProfileKind = AppProfileKind.UNKNOWN,
    val multiOpen: Boolean = false,
) {
    val target get() = ApplicationTarget(packageName, userId)
    val verified get() = source != AppChoiceSource.CONFIGURED && userId != null && userId >= 0
}

fun mergeApplicationChoices(
    installed: List<AppChoice>, observed: List<AppChoice>, targets: List<ApplicationTarget>,
): List<AppChoice> {
    val known = (installed + observed).filter { it.verified }.distinctBy { it.target }
    val names = known.associate { it.packageName to it.name }
    val mainPackages = known.filter { it.userId == 0 }.mapTo(mutableSetOf()) { it.packageName }
    return (known.map { app ->
        app.copy(multiOpen = app.profileKind == AppProfileKind.CLONE ||
            (app.profileKind == AppProfileKind.UNKNOWN && app.userId != 0 && app.packageName in mainPackages))
    } + targets.map { AppChoice(it.packageName, names[it.packageName] ?: it.packageName, it.userId) })
        .distinctBy { it.target }.sortedWith(compareBy({ it.name.lowercase() }, { it.userId ?: -1 }))
}

// 规则中的手动配置不代表该用户实际安装了应用，不能作为候选实例的证据。
fun applicationChoicesForScope(choices: List<AppChoice>, scope: String, userId: Int?): List<AppChoice> {
    val known = choices.filter { it.verified }
    return when (scope) {
        "all" -> known.distinctBy { it.packageName }.map { it.copy(userId = null, multiOpen = false) }
        "main" -> known.filter { it.userId == 0 }
        "custom" -> if (userId == null || userId < 0) emptyList() else known.filter { it.userId == userId }
        else -> known
    }
}

fun applicationChoiceLabel(app: AppChoice): String = app.name + when {
    app.userId == null -> ""
    app.multiOpen -> "（多开）"
    app.profileKind == AppProfileKind.WORK -> "（工作）"
    app.profileKind == AppProfileKind.PRIVATE -> "（私密）"
    else -> ""
}

fun targetCaption(target: ApplicationTarget, choices: List<AppChoice>, fallbackName: String? = null): String {
    val exact = choices.firstOrNull { it.target == target }
    return exact?.let { applicationChoiceLabel(it.copy(name = fallbackName ?: it.name)) }
        ?: fallbackName ?: choices.firstOrNull { it.packageName == target.packageName }?.name ?: target.packageName
}
