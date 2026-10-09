package io.github.notifybox.ui

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.*
import java.util.Locale

object SearchIndex {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    private val cache = object : LinkedHashMap<String, Pair<String, String>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 512
    }
    @Synchronized fun matches(text: String, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        return q.isEmpty() || indexText(text).contains(q)
    }
    @Synchronized fun indexText(text: String): String {
        val literal = text.lowercase(Locale.ROOT)
        // 通知长正文只做字面搜索；名称转换缓存限定长度与数量。
        if (text.length > 256) return literal
        val p = cache.getOrPut(text) {
            val full = StringBuilder()
            val initials = StringBuilder()
            text.forEach { ch ->
                val syllable = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull() }.getOrNull()
                val value = syllable ?: ch.lowercaseChar().toString()
                full.append(value); initials.append(value.firstOrNull() ?: ch)
            }
            full.toString() to initials.toString()
        }
        return literal + "\n" + p.first + "\n" + p.second
    }
}
