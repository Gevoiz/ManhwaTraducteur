package com.manhwatraducteur

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

/**
 * Vue plein écran (non tactile) qui dessine un cartouche blanc avec le texte traduit
 * à l'emplacement exact de chaque bulle d'origine.
 */
class TranslationOverlayView(context: Context) : View(context) {

    data class Item(val bubble: TextBubble, val translated: String)

    private class Placed(val box: RectF, val layout: StaticLayout, val textLeft: Float, val textTop: Float)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt(); style = Paint.Style.STROKE; strokeWidth = context.dp(1).toFloat()
    }
    private val typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val pad = context.dp(5)
    private val radius = context.dp(10).toFloat()
    private val minTextSize = context.sp(9)
    private val maxTextSize = context.sp(26)

    private var placed: List<Placed> = emptyList()
    private val loc = IntArray(2)

    /** Décalage vertical appliqué à toutes les traductions (suivi du défilement). */
    var offsetY = 0
        private set

    fun setOffset(dy: Int) {
        if (dy == offsetY) return
        offsetY = dy
        invalidate()
    }

    /** Position actuelle des cartouches à l'écran, élargie d'une marge (pour les masquer du suivi). */
    fun boxRects(marginX: Int, marginY: Int): List<Rect> = placed.map {
        Rect(
            it.box.left.toInt() - marginX, it.box.top.toInt() + offsetY - marginY,
            it.box.right.toInt() + marginX, it.box.bottom.toInt() + offsetY + marginY,
        )
    }

    val hasItems get() = placed.isNotEmpty()

    fun setItems(items: List<Item>, screenWidth: Int) {
        offsetY = 0
        placed = items.map { place(it, screenWidth) }
        invalidate()
    }

    fun clear() {
        offsetY = 0
        if (placed.isEmpty()) return
        placed = emptyList()
        invalidate()
    }

    private fun place(item: Item, screenW: Int): Placed {
        val r = item.bubble.rect
        val margin = context.dp(4)
        val boxW = max(r.width() + pad * 2, context.dp(90)).coerceAtMost(screenW - margin * 2)
        val innerW = boxW - pad * 2
        val baseH = r.height() + pad * 2
        val innerH = baseH - pad * 2

        // On part d'une taille proche du texte d'origine et on réduit jusqu'à ce que ça rentre.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = this@TranslationOverlayView.typeface }
        var size = (item.bubble.lineHeight * 0.8f).coerceIn(minTextSize, maxTextSize)
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(item.translated, 0, item.translated.length, paint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.95f)
                .build()
            if (layout.height <= innerH || size <= minTextSize) break
            size -= 1f
        }

        val boxH = max(baseH, layout.height + pad * 2)
        val left = (r.centerX() - boxW / 2).coerceIn(margin, max(margin, screenW - boxW - margin))
        val top = r.top - pad
        val box = RectF(left.toFloat(), top.toFloat(), (left + boxW).toFloat(), (top + boxH).toFloat())
        val textTop = box.top + (boxH - layout.height) / 2f
        return Placed(box, layout, box.left + pad, textTop)
    }

    override fun onDraw(canvas: Canvas) {
        if (placed.isEmpty()) return
        // Les coordonnées OCR sont celles de l'écran physique : on compense la position de la fenêtre.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), (offsetY - loc[1]).toFloat())
        for (p in placed) {
            canvas.drawRoundRect(p.box, radius, radius, boxPaint)
            canvas.drawRoundRect(p.box, radius, radius, strokePaint)
            canvas.save()
            canvas.translate(p.textLeft, p.textTop)
            p.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
