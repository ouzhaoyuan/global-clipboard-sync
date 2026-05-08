package com.clipboard.sync

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardA11y"
        private const val DEBOUNCE_MS = 800L
        private var lastSentText: String = ""
        private var lastSentTime: Long = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClipboardText: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Monitor copy-related events
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                checkClipboard()
            }
        }
    }

    private fun checkClipboard() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, DEBOUNCE_MS)
    }

    private val checkRunnable = Runnable {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return@Runnable

            val clip = clipboard.primaryClip ?: return@Runnable
            if (clip.itemCount == 0) return@Runnable

            val text = clip.getItemAt(0)?.text?.toString() ?: return@Runnable

            if (text.isBlank()) return@Runnable

            // Dedup: same text or recent enough
            val now = System.currentTimeMillis()
            if (text == lastSentText && now - lastSentTime < 3000) return@Runnable
            if (text == lastClipboardText) return@Runnable

            lastClipboardText = text
            lastSentText = text
            lastSentTime = now

            Log.d(TAG, "Detected clipboard change: ${text.take(30)}...")

            // Publish via MqttManager
            val mqttManager = MqttManager.getInstance(this)
            mqttManager?.publishClipboard(text)

        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard", e)
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(checkRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkRunnable)
    }
}
