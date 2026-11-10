package com.manhwatraducteur

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Estime de combien de pixels le contenu de l'écran a défilé verticalement depuis la capture
 * de référence (celle qui a servi à la traduction), pour que les traductions suivent la page.
 *
 * Principe : on échantillonne la luminosité de [K] colonnes sur toute la hauteur de l'écran,
 * puis on cherche le décalage vertical qui fait le mieux coïncider l'image actuelle avec la
 * référence (d'abord grossièrement par blocs de 8 lignes, puis au pixel près).
 * Les zones couvertes par nos propres cartouches et par la bulle sont ignorées ([masks]).
 */
class ScrollTracker {

    sealed interface Result {
        /**
         * Le contenu qui était à la ligne y est maintenant à la ligne y + dy.
         * [lead] : petite avance (en px) à ajouter pour compenser le temps de traitement pendant
         * un glissement rapide (0 quand la page est immobile).
         */
        data class Offset(val dy: Int, val lead: Int = 0) : Result
        /** Impossible de retrouver la page (changement de chapitre, zoom, page vide…). */
        data object Lost : Result
    }

    private companion object {
        const val K = 24          // colonnes échantillonnées
        const val C = 8           // hauteur d'un bloc pour la recherche grossière
        const val TEXTURE = 5     // variation horizontale minimale pour qu'une ligne soit exploitable
        const val COARSE_THR = 12 // écart moyen de luminosité toléré (blocs)
        const val FINE_THR = 16   // écart moyen toléré (lignes)
        const val NEAR = 40       // demi-largeur de la recherche ciblée, en blocs (±320 px)
        const val LEAD_MS = 16.0  // avance ≈ 1 image à 60 Hz
        const val MAX_LEAD = 48
    }

    private val lock = Any()
    private var width = 0
    private var height = 0
    private val cols = IntArray(K)

    private var ref: IntArray? = null
    private var refTex = BooleanArray(0)
    private var refCoarse = IntArray(0)
    private var refCoarseTex = BooleanArray(0)

    // Tampons réutilisés d'une image à l'autre
    private var cur = IntArray(0)
    private var curValid = BooleanArray(0)
    private var curCoarse = IntArray(0)
    private var curCoarseValid = BooleanArray(0)

    private var lastOffset = 0
    private var lastTimeNs = 0L
    private var velocity = 0.0  // px par milliseconde (lissée)

    /** Zones à ignorer dans les images courantes (coordonnées écran). */
    @Volatile var masks: List<Rect> = emptyList()

    val active: Boolean get() = ref != null

    fun setReference(bmp: Bitmap) = synchronized(lock) {
        width = bmp.width; height = bmp.height
        for (k in 0 until K) cols[k] = (width * (0.04 + 0.92 * k / (K - 1))).toInt().coerceIn(0, width - 1)

        val lum = IntArray(height * K)
        val column = IntArray(height)
        for (k in 0 until K) {
            bmp.getPixels(column, 0, 1, cols[k], 0, 1, height)
            for (y in 0 until height) lum[y * K + k] = luma(column[y])
        }
        val allValid = BooleanArray(height * K) { true }
        ref = lum
        refTex = textured(lum, height)
        refCoarse = IntArray((height / C) * K)
        coarse(lum, allValid, height, refCoarse, BooleanArray(refCoarse.size))
        refCoarseTex = textured(refCoarse, height / C)

        cur = IntArray(height * K); curValid = BooleanArray(height * K)
        curCoarse = IntArray(refCoarse.size); curCoarseValid = BooleanArray(refCoarse.size)
        lastOffset = 0; lastTimeNs = 0L; velocity = 0.0
    }

    fun reset() = synchronized(lock) { ref = null }

    /** Analyse une image du flux de capture. Renvoie null si aucun suivi n'est en cours. */
    fun track(img: Image): Result? = synchronized(lock) {
        val r = ref ?: return null
        if (img.width != width || img.height != height) return Result.Lost

        // 1. Échantillonnage de l'image courante
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        for (y in 0 until height) {
            val base = y * rs
            for (k in 0 until K) {
                val i = base + cols[k] * ps
                val red = buf.get(i).toInt() and 0xFF
                val green = buf.get(i + 1).toInt() and 0xFF
                val blue = buf.get(i + 2).toInt() and 0xFF
                cur[y * K + k] = (red * 77 + green * 150 + blue * 29) shr 8
            }
        }
        java.util.Arrays.fill(curValid, true)
        for (m in masks) {
            val y0 = max(0, m.top); val y1 = min(height, m.bottom)
            for (k in 0 until K) {
                if (cols[k] < m.left || cols[k] >= m.right) continue
                for (y in y0 until y1) curValid[y * K + k] = false
            }
        }

        // 2. Recherche grossière. On cherche d'abord autour de la position prévue d'après la vitesse
        //    du glissement (rapide), et on ne balaie tout l'écran que si on n'y trouve rien.
        val rows = height / C
        coarse(cur, curValid, height, curCoarse, curCoarseValid)
        val maxD = (rows * 0.85).toInt()
        val now = img.timestamp
        val dtMs = if (lastTimeNs > 0) ((now - lastTimeNs) / 1_000_000.0).coerceIn(0.0, 200.0) else 0.0
        val predicted = ((lastOffset + velocity * dtMs) / C).toInt()
        var best = coarseSearch(rows, (predicted - NEAR).coerceAtLeast(-maxD), (predicted + NEAR).coerceAtMost(maxD), predicted)
        if (!best.accepted()) best = coarseSearch(rows, -maxD, maxD, lastOffset / C)
        if (!best.accepted()) return Result.Lost

        // 3. Affinage au pixel près autour du meilleur bloc
        var fineD = best.d * C; var fineScore = Int.MIN_VALUE; var fineMatch = 0; var fineConsidered = 0
        for (d in best.d * C - C - 2..best.d * C + C + 2) {
            val (match, considered) = score(r, refTex, cur, curValid, height, d, FINE_THR)
            val s = 2 * match - considered
            if (s > fineScore) { fineScore = s; fineD = d; fineMatch = match; fineConsidered = considered }
        }
        if (fineMatch < 20 || fineMatch < 0.5 * fineConsidered) return Result.Lost

        // 4. Vitesse lissée → petite avance pour que les cartouches ne traînent pas derrière la page
        if (dtMs > 0) {
            val v = (fineD - lastOffset) / dtMs
            velocity = if (abs(fineD - lastOffset) <= 1) 0.0 else velocity * 0.4 + v * 0.6
        }
        lastOffset = fineD
        lastTimeNs = now
        val lead = (velocity * LEAD_MS).toInt().coerceIn(-MAX_LEAD, MAX_LEAD)
        Result.Offset(fineD, lead)
    }

    private class Best(val d: Int, val match: Int, val considered: Int) {
        fun accepted() = match >= 6 && match >= 0.6 * considered
    }

    private fun coarseSearch(rows: Int, from: Int, to: Int, prefer: Int): Best {
        var bestD = prefer; var bestScore = Int.MIN_VALUE; var bestMatch = 0; var bestConsidered = 0
        for (d in from..to) {
            val (match, considered) = score(refCoarse, refCoarseTex, curCoarse, curCoarseValid, rows, d, COARSE_THR)
            val s = 2 * match - considered
            if (s > bestScore || (s == bestScore && abs(d - prefer) < abs(bestD - prefer))) {
                bestScore = s; bestD = d; bestMatch = match; bestConsidered = considered
            }
        }
        return Best(bestD, bestMatch, bestConsidered)
    }

    private fun score(
        a: IntArray, aTex: BooleanArray, b: IntArray, bValid: BooleanArray,
        rows: Int, d: Int, thr: Int,
    ): Pair<Int, Int> {
        var match = 0; var considered = 0
        val start = max(0, -d); val end = min(rows, rows - d)
        for (r in start until end) {
            if (!aTex[r]) continue
            val ai = r * K; val bi = (r + d) * K
            var sum = 0; var n = 0
            for (k in 0 until K) {
                if (!bValid[bi + k]) continue
                sum += abs(a[ai + k] - b[bi + k]); n++
            }
            if (n < K / 3) continue
            considered++
            if (sum < thr * n) match++
        }
        return match to considered
    }

    private fun coarse(src: IntArray, valid: BooleanArray, rows: Int, out: IntArray, outValid: BooleanArray) {
        val cRows = rows / C
        for (cr in 0 until cRows) for (k in 0 until K) {
            var sum = 0; var n = 0
            for (dy in 0 until C) {
                val i = (cr * C + dy) * K + k
                if (valid[i]) { sum += src[i]; n++ }
            }
            val o = cr * K + k
            outValid[o] = n >= C / 2
            out[o] = if (n > 0) sum / n else 0
        }
    }

    private fun textured(a: IntArray, rows: Int) = BooleanArray(rows) { r ->
        var s = 0
        for (k in 0 until K - 1) s += abs(a[r * K + k] - a[r * K + k + 1])
        s > TEXTURE * (K - 1)
    }

    private fun luma(argb: Int): Int {
        val red = (argb shr 16) and 0xFF; val green = (argb shr 8) and 0xFF; val blue = argb and 0xFF
        return (red * 77 + green * 150 + blue * 29) shr 8
    }
}
