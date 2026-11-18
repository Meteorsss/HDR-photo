package com.meteorsss.hdrphoto

import kotlin.math.abs
import kotlin.math.roundToLong

internal data class RefreshChanges(val added: Int, val removed: Int, val updated: Int) {
    fun describe(scope: String): String {
        val changes = listOfNotNull(if (added > 0) "新增 $added 张" else null,
            if (removed > 0) "移除 $removed 张" else null, if (updated > 0) "更新 $updated 张" else null)
        return "$scope 已刷新，" + if (changes.isEmpty()) "无变化" else changes.joinToString("、")
    }
    companion object {
        fun <T : Any, K> compare(before: List<T>, after: List<T>, key: (T) -> K): RefreshChanges {
            val old = before.associateBy(key).toMutableMap()
            var added = 0; var updated = 0
            for (item in after) { val previous = old.remove(key(item)); if (previous == null) added++ else if (item != previous) updated++ }
            return RefreshChanges(added, old.size, updated)
        }
    }
}

/** One offset drives both pages; positive step means the next page enters from the right. */
internal object PageSlide {
    fun targetOffset(step: Int, width: Float) = -step * width
    fun neighborOffset(step: Int, width: Float, offset: Float) = step * width + offset
    fun duration(from: Float, to: Float, width: Float): Long =
        (130 + 170 * (abs(to - from) / width.coerceAtLeast(1f)).coerceIn(0f, 1f)).roundToLong()
}
