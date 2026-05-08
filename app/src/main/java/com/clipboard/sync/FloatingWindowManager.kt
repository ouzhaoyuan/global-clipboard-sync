package com.clipboard.sync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

class FloatingWindowManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val ICON_SIZE_DP = 42
        private const val DOUBLE_CLICK_THRESHOLD = 300L
        private const val ALERT_BLINK_INTERVAL = 600L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var isAlert = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private var lastClickTime = 0L
    private var clickCount = 0
    private val clickHandler = Handler(Looper.getMainLooper())

    private val blinkHandler = Handler(Looper.getMainLooper())
    private var blinkRunnable: Runnable? = null
    private var blinkVisible = true

    var onSingleClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        val density = context.resources.displayMetrics.density
        val iconSizePx = (ICON_SIZE_DP * density).toInt()

        floatingView = ImageView(context).apply {
            setImageResource(R.drawable.ic_clipboard)
            setBackgroundColor(0x80000000.toInt()) // 半透明黑色背景
            setPadding(4, 4, 4, 4)
        }

        val flagType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            iconSizePx,
            iconSizePx,
            flagType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        floatingView?.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        try {
            windowManager.addView(floatingView, layoutParams)
            isShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating window", e)
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                    isDragging = true
                }

                if (isDragging) {
                    layoutParams?.x = initialX + dx.toInt()
                    layoutParams?.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(floatingView, layoutParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update layout", e)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    handleClick()
                }
                return true
            }
        }
        return false
    }

    private fun handleClick() {
        val now = System.currentTimeMillis()
        clickCount++

        if (clickCount == 1) {
            lastClickTime = now
            clickHandler.postDelayed({
                if (clickCount == 1) {
                    Log.d(TAG, "Single click")
                    onSingleClick?.invoke()
                }
                clickCount = 0
            }, DOUBLE_CLICK_THRESHOLD)
        } else if (clickCount >= 2) {
            clickHandler.removeCallbacksAndMessages(null)
            clickCount = 0

            if (now - lastClickTime < DOUBLE_CLICK_THRESHOLD) {
                Log.d(TAG, "Double click")
                onDoubleClick?.invoke()
            }
        }
    }

    fun showAlert() {
        if (!isShowing || isAlert) return
        isAlert = true

        stopBlink()

        blinkRunnable = object : Runnable {
            override fun run() {
                if (!isAlert || floatingView == null) return
                blinkVisible = !blinkVisible
                floatingView?.visibility = if (blinkVisible) View.VISIBLE else View.INVISIBLE

                if (blinkVisible) {
                    floatingView?.setImageResource(R.drawable.ic_clipboard_alert)
                    floatingView?.setBackgroundColor(0x80FF9800.toInt()) // 橙色背景
                }

                blinkHandler.postDelayed(this, ALERT_BLINK_INTERVAL)
            }
        }

        blinkHandler.post(blinkRunnable!!)
    }

    fun clearAlert() {
        if (!isAlert) return
        isAlert = false
        stopBlink()

        floatingView?.apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_clipboard)
            setBackgroundColor(0x80000000.toInt())
        }
    }

    private fun stopBlink() {
        blinkRunnable?.let {
            blinkHandler.removeCallbacks(it)
            blinkRunnable = null
        }
        floatingView?.visibility = View.VISIBLE
    }

    fun hide() {
        if (!isShowing) return

        stopBlink()
        clickHandler.removeCallbacksAndMessages(null)

        try {
            floatingView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove floating window", e)
        }

        floatingView = null
        layoutParams = null
        isShowing = false
        isAlert = false
    }

    fun vibrate() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed", e)
        }
    }
}
