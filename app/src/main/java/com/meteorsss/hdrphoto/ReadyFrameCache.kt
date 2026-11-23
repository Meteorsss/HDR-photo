package com.meteorsss.hdrphoto

/** Main-thread cache of final decode results. Visible views own their frames independently. */
internal class ReadyFrameCache<K, V>(private val budget: Long, private val weight: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>()
    operator fun get(key: K): V? = entries[key]
    fun put(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > 1 && (entries.size > 2 || entries.values.sumOf(weight) > budget)) {
            entries.remove(entries.keys.first())
        }
    }
    fun retain(keys: Set<K>) { entries.keys.retainAll(keys) }
    fun clear() { entries.clear() }
}
