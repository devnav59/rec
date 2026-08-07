package com.devnav.rec

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** A small, movable control that always exposes mute and stop while screen monitoring is active. */
class FloatingOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onMutedChanged: (Boolean) -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var attached = false
    private var muted = false

    private val valueView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        text = "…"
    }

    private val muteView = actionView("🔊").apply {
        contentDescription = context.getString(R.string.floating_sound_on)
        setOnClickListener {
            muted = !muted
            renderMuteState()
            onMutedChanged(muted)
        }
    }

    private val stopView = actionView("×").apply {
        textSize = 25f
        contentDescription = context.getString(R.string.floating_stop)
        setOnClickListener { onStop() }
    }

    private val dragHandle = TextView(context).apply {
        text = "◉"
        textSize = 19f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(5), dp(4), dp(5))
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.rgb(20, 51, 78))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }
        elevation = dp(8).toFloat()
        addView(dragHandle)
        addView(valueView)
        addView(muteView)
        addView(stopView)
    }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(120)
    }

    init {
        installDragHandler()
    }

    fun show() {
        if (attached) return
        windowManager.addView(root, layoutParams)
        attached = true
    }

    fun showSearching() {
        valueView.text = "…"
        valueView.setTextColor(Color.rgb(224, 239, 255))
    }

    fun showValue(value: String) {
        valueView.text = NumberParser.toPersianDigits(value)
        valueView.setTextColor(Color.WHITE)
    }

    fun remove() {
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        attached = false
    }

    private fun renderMuteState() {
        muteView.text = if (muted) "🔇" else "🔊"
        muteView.contentDescription = context.getString(
            if (muted) R.string.floating_sound_off else R.string.floating_sound_on
        )
    }

    private fun actionView(initialText: String): TextView = TextView(context).apply {
        text = initialText
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        isClickable = true
        isFocusable = true
    }

    private fun installDragHandler() {
        val touchSlop = dp(4)
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f

        dragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                        layoutParams.x = startX + deltaX
                        layoutParams.y = (startY + deltaY).coerceAtLeast(0)
                        if (attached) runCatching { windowManager.updateViewLayout(root, layoutParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.performClick()
                    true
                }

                else -> true
            }
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
