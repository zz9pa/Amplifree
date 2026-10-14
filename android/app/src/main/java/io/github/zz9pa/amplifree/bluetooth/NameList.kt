package io.github.zz9pa.amplifree.bluetooth

/** Helpers for the list of tone names shown in the Tones tab. */
object NameList {
    /**
     * The names we already know, updated with the ones just read. A slot that was not re-read keeps its
     * old name, so a partial scan can only add or change entries, never make them disappear.
     */
    fun merge(known: Map<Int, String>, found: Map<Int, String>): List<Pair<Int, String>> =
        (known + found).entries.sortedBy { it.key }.map { it.key to it.value }
}
