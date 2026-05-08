package com.clipboard.sync

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardA11y"
        private const val DEBOUNCE_MS = 500L
        private var instance: ClipboardAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClipboardText: String = ""
    private var lastPublishTime: Long = 0
    private var clipboardManager: ClipboardManager? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d(TAG, "onPrimaryClipChanged triggered")
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, DEBOUNCE_MS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        ClipboardSyncService.start(this)
    }

    private val checkRunnable = Runnable {
        try {
            val cm = clipboardManager ?: return@Runnable
            if (!cm.hasPrimaryClip()) return@Runnable

            val clip = cm.primaryClip ?: return@Runnable
            if (clip.itemCount == 0) return@Runnable

            val text = clip.getItemAt(0)?.text?.toString() ?: return@Runnable
            if (text.isBlank()) return@Runnable

            val now = System.currentTimeMillis()
            if (text == lastClipboardText) return@Runnable
            if (now - lastPublishTime < 3000) return@Runnable

            lastClipboardText = text
            lastPublishTime = now

            Log.d(TAG, "Detected clipboard change: ${text.take(50)}...")

            val mqttManager = MqttManager.getInstance(this)
            mqttManager?.publishClipboard(text)

        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 纯靠OnPrimaryClipChangedListener，不依赖事件
    }

    override fun onInterrupt() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacks(checkRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacks(checkRunnable)
        Log.d(TAG, "AccessibilityService destroyed")
    }
}
