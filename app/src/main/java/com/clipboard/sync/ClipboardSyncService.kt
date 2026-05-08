package com.clipboard.sync

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

class ClipboardSyncService : Service() {

    companion object {
        private const val TAG = "ClipboardSyncService"
        private const val CHANNEL_ID = "clipboard_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.clipboard.sync.ACTION_STOP"
        private const val ACTION_WRITE_CLIPBOARD = "com.clipboard.sync.ACTION_WRITE_CLIPBOARD"

        fun start(context: Context) {
            val intent = Intent(context, ClipboardSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardSyncService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        /**
         * 通过启动Activity来写剪贴板
         * Android 10+ 后台Service不能直接写剪贴板，必须从前台Activity写入
         */
        fun writeClipboardViaActivity(context: Context) {
            val intent = Intent(context, ClipboardWriteActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            context.startActivity(intent)
        }
    }

    private var mqttManager: MqttManager? = null
    private var floatingWindowManager: FloatingWindowManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        mqttManager = MqttManager.getInstance(this)

        try {
            floatingWindowManager = FloatingWindowManager(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create FloatingWindowManager", e)
        }

        mqttManager?.setOnNewClipboardReceivedListener { text ->
            Log.d(TAG, "New clipboard received: ${text.take(30)}...")
            floatingWindowManager?.showAlert()
        }

        floatingWindowManager?.onSingleClick = {
            writeClipboardFromCached()
        }

        floatingWindowManager?.onDoubleClick = {
            shutdown()
        }

        mqttManager?.connect()

        try {
            floatingWindowManager?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating window", e)
        }
    }

    private fun writeClipboardFromCached() {
        val cachedText = mqttManager?.getCachedText()
        if (cachedText.isNullOrBlank()) {
            Toast.makeText(this, "没有新的剪贴板内容", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: 后台不能直接写剪贴板，必须通过前台Activity
            writeClipboardViaActivity(this)
        } else {
            // Android 9及以下可以直接写
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("clipboard_sync", cachedText)
                clipboard.setPrimaryClip(clip)
                mqttManager?.clearCachedText()
                floatingWindowManager?.clearAlert()
                floatingWindowManager?.vibrate()
                Toast.makeText(this, "已写入剪贴板", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write clipboard", e)
                Toast.makeText(this, "写入剪贴板失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun shutdown() {
        Log.d(TAG, "Shutting down service")
        floatingWindowManager?.hide()
        MqttManager.destroyInstance()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        floatingWindowManager?.hide()
        MqttManager.destroyInstance()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "剪贴板同步服务运行通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_clipboard)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }
}
