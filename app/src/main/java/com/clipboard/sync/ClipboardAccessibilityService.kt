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
        private const val POLL_INTERVAL_MS = 2000L
        private const val DEBOUNCE_MS = 500L
        private var instance: ClipboardAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClipboardText: String = ""
    private var lastPublishTime: Long = 0
    private var clipboardManager: ClipboardManager? = null

    // 双保险：OnPrimaryClipChangedListener + 轮询
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

        // 方式1：注册剪贴板变化监听
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
            Log.d(TAG, "OnPrimaryClipChangedListener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clip listener", e)
        }

        // 方式2：轮询兜底（每2秒检查一次剪贴板内容是否变化）
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)

        // 启动前台服务
        ClipboardSyncService.start(this)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkClipboardContent()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 方式3：无障碍事件也触发检查（任何UI事件都检查下剪贴板）
        if (event != null) {
            handler.removeCallbacks(checkRunnable)
            handler.postDelayed(checkRunnable, DEBOUNCE_MS)
        }
    }

    private val checkRunnable = Runnable {
        checkClipboardContent()
    }

    private fun checkClipboardContent() {
        try {
            val cm = clipboardManager ?: return
            if (!cm.hasPrimaryClip()) return

            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return

            val text = clip.getItemAt(0)?.text?.toString() ?: return
            if (text.isBlank()) return

            // 去重：同一内容不重复发送
            if (text == lastClipboardText) return

            val now = System.currentTimeMillis()
            if (now - lastPublishTime < 2000) return

            lastClipboardText = text
            lastPublishTime = now

            Log.d(TAG, "Detected clipboard change: ${text.take(50)}...")

            val mqttManager = MqttManager.getInstance(this)
            if (mqttManager?.isConnected() == true) {
                mqttManager.publishClipboard(text)
            } else {
                Log.w(TAG, "MQTT not connected, trying reconnect...")
                mqttManager?.connect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard", e)
        }
    }

    override fun onInterrupt() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "AccessibilityService destroyed")
    }
}
