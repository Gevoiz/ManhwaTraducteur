package com.manhwatraducteur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnLayout
import kotlin.math.max

/**
 * Affiche chaque traduction dans sa propre petite fenêtre, 100 % opaque, posée sur la bulle d'origine.
 *
 * Pourquoi une fenêtre par cartouche : depuis Android 12, une superposition qui laisse passer les
 * touchers doit être opaque à 80 % maximum sur toute sa surface. Avec une fenêtre plein écran, les
 * cartouches étaient donc légèrement transparents. Avec des petites fenêtres, la limite ne concerne
 * que la surface des cartouches : ils peuvent être totalement blancs, et le reste de l'écran défile
 * normalement (un glissement qui commence pile sur un cartouche est ignoré par Android).
 */
class TranslationOverlay(private val context: Context, private val wm: WindowManager) {

    data class Item(val bubble: TextBubble, val translated: String)

    private class Placed(val box: Rect, val layout: StaticLayout, val textTop: Float)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val pad = context.dp(5)          // marge du cartouche autour de la bulle d'origine (ne change pas)
    private val textPad = context.dp(3)      // marge du texte à l'intérieur du cartouche
    private val radius = context.dp(10).toFloat()
    private val minTextSize = context.sp(9)
    private val maxTextSize = context.sp(34)

    private val windows = mutableListOf<Pair<BoxView, WindowManager.LayoutParams>>()

    // Écart éventuel entre les coordonnées demandées et la position réelle à l'écran (mesuré une fois).
    private var corrX = 0
    private var corrY = 0
    private var calibrated = false

    /** Décalage vertical appliqué à toutes les traductions (suivi du défilement). */
    var offsetY = 0
        private set

    val hasItems get() = windows.isNotEmpty()

    fun setItems(items: List<Item>, screenWidth: Int) {
        clear()
        for (item in items) {
            val p = place(item, screenWidth)
            val view = BoxView(p)
            val params = WindowManager.LayoutParams(
                p.box.width(), p.box.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = p.box.left + corrX
                y = p.box.top + corrY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            runCatching { wm.addView(view, params) }.onSuccess { windows += view to params }
        }
        if (!calibrated) windows.firstOrNull()?.first?.let { calibrate(it) }
    }

    fun clear() {
        offsetY = 0
        for ((view, _) in windows) runCatching { wm.removeView(view) }
        windows.clear()
    }

    fun setOffset(dy: Int) {
        if (dy == offsetY) return
        offsetY = dy
        relayout()
    }

    /** Position actuelle des cartouches à l'écran, élargie d'une marge (pour les masquer du suivi). */
    fun boxRects(marginX: Int, marginY: Int): List<Rect> = windows.map { (view, _) ->
        val b = view.placed.box
        Rect(b.left - marginX, b.top + offsetY - marginY, b.right + marginX, b.bottom + offsetY + marginY)
    }

    private fun relayout() {
        for ((view, params) in windows) {
            params.x = view.placed.box.left + corrX
            params.y = view.placed.box.top + offsetY + corrY
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    /** Selon les appareils, (0, 0) n'est pas forcément le coin de l'écran : on mesure et on corrige. */
    private fun calibrate(view: BoxView) = view.doOnLayout {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val wantX = view.placed.box.left
        val wantY = view.placed.box.top + offsetY
        corrX += wantX - loc[0]
        corrY += wantY - loc[1]
        calibrated = true
        if (loc[0] != wantX || loc[1] != wantY) relayout()
    }

    private fun place(item: Item, screenW: Int): Placed {
        val r = item.bubble.rect
        val margin = context.dp(4)
        val boxW = max(r.width() + pad * 2, context.dp(90)).coerceAtMost(screenW - margin * 2)
        val innerW = boxW - textPad * 2
        val baseH = r.height() + pad * 2
        val innerH = baseH - textPad * 2

        // Le cartouche garde sa taille : on cherche la plus grande police qui y tient
        // (sans couper un mot en deux), plafonnée un peu au-dessus de la taille du texte d'origine.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = this@TranslationOverlay.typeface }
        val words = item.translated.split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun build(size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(item.translated, 0, item.translated.length, paint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.92f)
                .build()
        }
        fun fits(size: Float): Boolean {
            val layout = build(size)
            return layout.height <= innerH && words.all { paint.measureText(it) <= innerW }
        }
        val ceiling = (item.bubble.lineHeight * 1.3f).coerceIn(minTextSize, maxTextSize)
        var lo = minTextSize; var hi = ceiling
        if (fits(hi)) lo = hi
        else repeat(10) {
            val mid = (lo + hi) / 2
            if (fits(mid)) lo = mid else hi = mid
        }
        val layout = build(lo)

        val boxH = max(baseH, layout.height + textPad * 2)
        val left = (r.centerX() - boxW / 2).coerceIn(margin, max(margin, screenW - boxW - margin))
        val top = r.top - pad
        val box = Rect(left, top, left + boxW, top + boxH)
        return Placed(box, layout, (boxH - layout.height) / 2f)
    }

    @SuppressLint("ViewConstructor")
    private inner class BoxView(val placed: Placed) : View(context) {
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            canvas.save()
            canvas.translate(textPad.toFloat(), placed.textTop)
            placed.layout.draw(canvas)
            canvas.restore()
        }
    }
}
