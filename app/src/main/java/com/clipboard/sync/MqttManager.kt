package com.clipboard.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

/**
 * MQTT管理器 - 使用原生Paho客户端 + 协程
 * 不使用MqttAndroidClient（内部依赖废弃的IntentService，Android 14+必崩）
 */
class MqttManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MqttManager"
        private const val BROKER_URL = "tcp://broker.hivemq.com:1883"
        private const val TOPIC = "global/clipboard/sync"
        private const val CLIENT_ID_PREFIX = "gcs_"

        @Volatile
        private var instance: MqttManager? = null

        fun getInstance(context: Context): MqttManager? {
            if (instance == null) {
                synchronized(MqttManager::class.java) {
                    if (instance == null) {
                        try {
                            instance = MqttManager(context.applicationContext)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create MqttManager", e)
                            return null
                        }
                    }
                }
            }
            return instance
        }

        fun destroyInstance() {
            instance?.disconnect()
            instance = null
        }
    }

    val deviceId: String
    private var mqttClient: MqttClient? = null
    private var isConnected = false
    private var cachedText: String? = null
    private var onNewClipboardReceived: ((String) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    init {
        // 持久化deviceId，避免每次重启都变
        val prefs = context.getSharedPreferences("gcs_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    fun setOnNewClipboardReceivedListener(listener: (String) -> Unit) {
        onNewClipboardReceived = listener
    }

    fun getCachedText(): String? = cachedText

    fun clearCachedText() {
        cachedText = null
    }

    fun connect() {
        if (mqttClient?.isConnected == true) return
        scope.launch {
            tryConnect()
        }
    }

    private suspend fun tryConnect() {
        try {
            val clientId = CLIENT_ID_PREFIX + deviceId.take(8)
            val persistence = MemoryPersistence()
            mqttClient = MqttClient(BROKER_URL, clientId, persistence)

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false // 我们自己管重连
                isCleanSession = true
                connectionTimeout = 30
                keepAliveInterval = 60
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                    isConnected = false
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // no-op
                }
            })

            mqttClient?.connect(options)
            isConnected = true
            Log.d(TAG, "MQTT connected successfully")

            mqttClient?.subscribe(TOPIC, 1)
            Log.d(TAG, "Subscribed to $TOPIC")

        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect failed", e)
            isConnected = false
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            Log.d(TAG, "Attempting MQTT reconnect...")
            tryConnect()
        }
    }

    fun publishClipboard(text: String) {
        scope.launch {
            if (!isConnected && mqttClient?.isConnected != true) {
                Log.w(TAG, "Not connected, cannot publish")
                return@launch
            }
            try {
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("text", text)
                    put("timestamp", System.currentTimeMillis())
                }
                val message = MqttMessage(json.toString().toByteArray()).apply {
                    qos = 1
                }
                mqttClient?.publish(TOPIC, message)
                Log.d(TAG, "Published clipboard: ${text.take(30)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Publish failed", e)
            }
        }
    }

    private fun handleMessage(topic: String?, message: MqttMessage?) {
        if (topic != TOPIC || message == null) return
        try {
            val json = JSONObject(String(message.payload))
            val senderDeviceId = json.getString("deviceId")
            val text = json.getString("text")

            if (senderDeviceId == deviceId) {
                Log.d(TAG, "Ignoring own message")
                return
            }

            Log.d(TAG, "Received clipboard from $senderDeviceId: ${text.take(30)}...")
            cachedText = text
            onNewClipboardReceived?.invoke(text)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        scope.launch {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect exception", e)
            }
            isConnected = false
            mqttClient = null
        }
        scope.cancel()
    }
}
