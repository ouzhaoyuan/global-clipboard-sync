package com.clipboard.sync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast

/**
 * 透明Activity，仅用于在Android 10+上从前台写入剪贴板
 * Android 10+规定只有前台应用才能写入剪贴板，
 * 后台Service调clipboard.setPrimaryClip()会静默失败或抛异常
 * 所以需要一个瞬间的透明Activity来做这个操作
 */
class ClipboardWriteActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardWriteActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        writeClipboardAndFinish()
    }

    private fun writeClipboardAndFinish() {
        try {
            val mqttManager = MqttManager.getInstance(this)
            val cachedText = mqttManager?.getCachedText()

            if (!cachedText.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("clipboard_sync", cachedText)
                clipboard.setPrimaryClip(clip)

                mqttManager?.clearCachedText()

                // 振动反馈
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }

                Toast.makeText(this, "已写入剪贴板", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Clipboard written successfully")
            } else {
                Toast.makeText(this, "没有新的剪贴板内容", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write clipboard", e)
            Toast.makeText(this, "写入剪贴板失败", Toast.LENGTH_SHORT).show()
        }

        // 立即关闭这个透明Activity
        finish()
    }
}
