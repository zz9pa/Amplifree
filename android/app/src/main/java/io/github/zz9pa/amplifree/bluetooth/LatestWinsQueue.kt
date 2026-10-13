package io.github.zz9pa.amplifree.bluetooth

/**
 * Pending changes keyed by control. Putting a key again replaces its old value and moves it to
 * the back, so the amp only ever receives the newest value of each control and the controls are
 * sent in the order they were last touched.
 */
class LatestWinsQueue<V> {
    private val map = LinkedHashMap<String, V>()

    @Synchronized fun put(key: String, value: V) {
        map.remove(key)
        map[key] = value
    }

    /** Oldest pending entry, removed; null when nothing is waiting. */
    @Synchronized fun take(): Pair<String, V>? {
        val it = map.entries.iterator()
        if (!it.hasNext()) return null
        val e = it.next()
        it.remove()
        return e.key to e.value
    }

    @Synchronized fun isEmpty(): Boolean = map.isEmpty()
    @Synchronized fun clear() = map.clear()
}
