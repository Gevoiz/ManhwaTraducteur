package com.manhwatraducteur

import kotlin.math.abs
import kotlin.math.min

/**
 * Mémoire des bulles déjà traduites. Après un défilement, la plupart des bulles visibles ont déjà
 * été traduites : on les réaffiche aussitôt, et seules les nouvelles partent à la traduction.
 * L'OCR d'une même bulle varie parfois d'une lettre ou deux : on tolère ces petits écarts.
 */
class TranslationCache(private val maxSize: Int = 400) {

    private var scope = ""
    private val map = LinkedHashMap<String, String>(64, 0.75f, true)

    /** Vide la mémoire si le moteur ou les langues ont changé. */
    fun setScope(s: String) {
        if (s != scope) { scope = s; map.clear() }
    }

    fun get(text: String): String? {
        val k = key(text)
        if (k.length < 2) return null
        map[k]?.let { return it }
        if (k.length < 8) return null
        val tolerance = k.length / 10
        var best: String? = null
        var bestKey: String? = null
        var bestD = Int.MAX_VALUE
        for ((ck, tr) in map.entries) {
            if (abs(ck.length - k.length) > tolerance) continue
            val d = distance(ck, k, tolerance)
            if (d < bestD) { bestD = d; best = tr; bestKey = ck }
        }
        if (bestD > tolerance) return null
        bestKey?.let { map[it] }   // marque comme récemment utilisée
        return best
    }

    fun put(text: String, translation: String) {
        val k = key(text)
        if (k.length < 2) return
        map[k] = translation
        val it = map.entries.iterator()
        while (map.size > maxSize && it.hasNext()) { it.next(); it.remove() }
    }

    private fun key(s: String) = buildString(s.length) {
        for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    /** Distance de Levenshtein, abandonnée dès qu'elle dépasse [limit]. */
    private fun distance(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
