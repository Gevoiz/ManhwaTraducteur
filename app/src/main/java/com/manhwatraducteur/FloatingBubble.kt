package com.manhwatraducteur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.hypot

/** Petite bulle flottante déplaçable : toucher = traduire, appui long = effacer. */
@SuppressLint("ClickableViewAccessibility")
class FloatingBubble(
    private val context: Context,
    private val wm: WindowManager,
    private val listener: Listener,
) {
    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onMoved()
    }

    private val size = context.dp(52)
    private var idleLabel = "FR"

    val view = TextView(context).apply {
        text = idleLabel
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xEEE63946.toInt())
            setStroke(context.dp(2), Color.WHITE)
        }
    }

    val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.dp(8)
        y = context.resources.displayMetrics.heightPixels / 3
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var dragging = false
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onLongPress()
    }

    init {
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longFired = false
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        view.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(view, params)
                        listener.onMoved()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (!dragging && !longFired) listener.onTap()
                }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            true
        }
    }

    fun attach() = wm.addView(view, params)

    /** Position de la bulle à l'écran. */
    fun screenRect(): android.graphics.Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return android.graphics.Rect(loc[0], loc[1], loc[0] + size, loc[1] + size)
    }
    fun detach() { runCatching { wm.removeView(view) } }

    fun setIdleLabel(label: String) {
        idleLabel = label
        view.text = label
    }

    /** Affiche un état temporaire ("…", "↓", "∅", "!") ou revient au libellé normal si null. */
    fun setStatus(status: String?) {
        view.text = status ?: idleLabel
        view.alpha = if (status == null) 1f else 0.8f
    }

    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }
}
