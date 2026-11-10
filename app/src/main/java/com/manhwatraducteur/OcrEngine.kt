package com.manhwatraducteur

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

/**
 * Un bloc de texte détecté à l'écran (en général : une bulle de dialogue).
 * [darkBackground] : texte clair sur fond sombre (le cartouche sera alors noir, texte blanc).
 */
data class TextBubble(val rect: Rect, val text: String, val lineHeight: Int, val darkBackground: Boolean = false)

/**
 * Le texte de la bulle est-il clair (blanc sur fond sombre/coloré) ?
 * On échantillonne une grille de points : la médiane donne la couleur du fond, et les points qui
 * s'en écartent nettement sont le texte. S'ils sont plus clairs que le fond → texte clair.
 * Sans texte bien visible, on se rabat sur la luminosité du fond.
 */
fun isDarkBackground(bitmap: Bitmap, rect: Rect): Boolean {
    val r = Rect(rect)
    if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.isEmpty) return false
    val nx = 24; val ny = 12
    val lum = IntArray(nx * ny)
    var n = 0
    for (j in 0 until ny) for (i in 0 until nx) {
        val x = r.left + (r.width() - 1) * i / (nx - 1)
        val y = r.top + (r.height() - 1) * j / (ny - 1)
        val c = bitmap.getPixel(x, y)
        lum[n++] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
    }
    lum.sort()
    val median = lum[n / 2]
    val lighter = lum.count { it > median + 60 }
    val darker = lum.count { it < median - 60 }
    return if (maxOf(lighter, darker) >= n * 0.02) lighter > darker else median < 110
}

class OcrEngine(val lang: String) {

    private val recognizer: TextRecognizer = when (lang) {
        TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val noSpaces = lang == TranslateLanguage.JAPANESE || lang == TranslateLanguage.CHINESE
    private val latin = lang == TranslateLanguage.ENGLISH

    private class Fragment(val rect: Rect, val lines: List<String>, val lineHeight: Int)

    /** @param ignoreTop les blocs entièrement au-dessus de cette ligne (barre d'état) sont ignorés. */
    suspend fun recognize(bitmap: Bitmap, ignoreTop: Int): List<TextBubble> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

        val fragments = result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            if (box.bottom <= ignoreTop) return@mapNotNull null
            val lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@mapNotNull null
            val lh = block.lines.mapNotNull { it.boundingBox?.height() }.average()
                .takeIf { !it.isNaN() }?.toInt() ?: (box.height() / lines.size)
            Fragment(Rect(box), lines, lh)
        }

        return merge(fragments).mapNotNull { f ->
            val text = join(f.lines)
            if (text.count { it.isLetter() } < 2) null
            else TextBubble(f.rect, text, f.lineHeight, isDarkBackground(bitmap, f.rect))
        }
    }

    /** ML Kit coupe parfois une même bulle en plusieurs blocs : on recolle ceux qui se touchent. */
    private fun merge(input: List<Fragment>): List<Fragment> {
        val list = input.sortedBy { it.rect.top }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                if (shouldMerge(a, b)) {
                    val (first, second) = if (a.rect.top <= b.rect.top) a to b else b to a
                    val r = Rect(a.rect).apply { union(b.rect) }
                    list[i] = Fragment(r, first.lines + second.lines, (a.lineHeight + b.lineHeight) / 2)
                    list.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return list
    }

    private fun shouldMerge(a: Fragment, b: Fragment): Boolean {
        val overlapX = min(a.rect.right, b.rect.right) - max(a.rect.left, b.rect.left)
        if (overlapX < 0.3 * min(a.rect.width(), b.rect.width())) return false
        val gapY = max(a.rect.top, b.rect.top) - min(a.rect.bottom, b.rect.bottom)
        return gapY < 0.9 * max(a.lineHeight, b.lineHeight)
    }

    private fun join(lines: List<String>): String {
        if (noSpaces) return lines.joinToString("")
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.isEmpty()) { sb.append(line); continue }
            if (latin && sb.endsWith("-") && line.firstOrNull()?.isLetter() == true) {
                sb.setLength(sb.length - 1); sb.append(line)   // mot coupé : "AMA-" + "ZING"
            } else {
                sb.append(' ').append(line)
            }
        }
        val text = sb.toString()
        return if (latin && isMostlyUpperCase(text)) sentenceCase(text) else text
    }

    private fun isMostlyUpperCase(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.length >= 3 && letters.count { it.isUpperCase() } > letters.length * 0.7
    }

    /** Les manhwas sont souvent en MAJUSCULES, ce qui dégrade la traduction : on remet en casse normale. */
    private fun sentenceCase(s: String): String {
        val sb = StringBuilder(s.length)
        var cap = true
        for (ch in s.lowercase()) {
            if (cap && ch.isLetter()) { sb.append(ch.uppercaseChar()); cap = false } else sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?') cap = true
        }
        return sb.toString().replace(Regex("\\bi\\b"), "I")
    }

    fun close() = recognizer.close()
}
