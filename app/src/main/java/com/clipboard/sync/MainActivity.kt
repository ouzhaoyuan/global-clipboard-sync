package com.clipboard.sync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "正在检查权限..."
            setTextSize(18f)
            setPadding(80, 120, 80, 80)
            setTextColor(0xFF333333.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        setContentView(statusView)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // 1. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("需要「显示在其他应用上层」权限\n\n请在设置中开启后返回")
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        // 2. 无障碍服务
        if (!isAccessibilityEnabled()) {
            updateStatus("需要「无障碍服务」权限\n\n请在设置中找到本应用并开启\n开启后返回即可")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        // 3. 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // 4. 启动服务
        ClipboardSyncService.start(this)

        updateStatus("已启动！正在连接MQTT服务器...")

        // 轮询连接状态
        handler.postDelayed(object : Runnable {
            override fun run() {
                val mqtt = MqttManager.getInstance(this@MainActivity)
                if (mqtt?.isConnected() == true) {
                    updateStatus("运行中 ✓\n\n剪贴板同步已就绪\n复制文字即可自动同步到其他设备\n\n悬浮窗：单击=粘贴 双击=退出")
                    // 连上后3秒自动关闭Activity
                    handler.postDelayed({ finish() }, 3000)
                    return
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun updateStatus(text: String) {
        statusView?.text = text
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "$packageName/.ClipboardAccessibilityService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            Log.d(TAG, "Enabled a11y: $enabledServices")
            return enabledServices.contains(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility", e)
            return false
        }
    }

    override fun onResume() {
        super.onResume()
        // 从权限设置页返回后重新检查
        handler.postDelayed({ checkAndRequestPermissions() }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
