package com.clipboard.sync

import android.content.Context
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.UUID

class MqttManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MqttManager"
        private const val BROKER_URL = "tcp://broker.hivemq.com:1883"
        private const val TOPIC = "global/clipboard/sync"
        private const val CLIENT_ID_PREFIX = "clipboard_sync_"

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

    val deviceId: String = UUID.randomUUID().toString()
    private var mqttClient: MqttAndroidClient? = null
    private var isConnected = false
    private var cachedText: String? = null
    private var onNewClipboardReceived: ((String) -> Unit)? = null

    fun setOnNewClipboardReceivedListener(listener: (String) -> Unit) {
        onNewClipboardReceived = listener
    }

    fun getCachedText(): String? = cachedText

    fun clearCachedText() {
        cachedText = null
    }

    fun connect() {
        if (mqttClient?.isConnected == true) return

        val clientId = CLIENT_ID_PREFIX + deviceId.take(8)
        mqttClient = MqttAndroidClient(context, BROKER_URL, clientId)

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 30
            keepAliveInterval = 60
        }

        mqttClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "MQTT connected: $serverURI (reconnect=$reconnect)")
                isConnected = true
                subscribe()
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "MQTT connection lost", cause)
                isConnected = false
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                handleMessage(topic, message)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // Delivery complete, no action needed
            }
        })

        try {
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT connect success")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT connect failed", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect exception", e)
        }
    }

    private fun subscribe() {
        try {
            mqttClient?.subscribe(TOPIC, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Subscribed to $TOPIC")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Subscribe failed", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe exception", e)
        }
    }

    fun publishClipboard(text: String) {
        if (!isConnected && mqttClient?.isConnected != true) {
            Log.w(TAG, "Not connected, cannot publish")
            return
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

            mqttClient?.publish(TOPIC, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Published clipboard: ${text.take(30)}...")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Publish failed", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Publish exception", e)
        }
    }

    private fun handleMessage(topic: String?, message: MqttMessage?) {
        if (topic != TOPIC || message == null) return

        try {
            val json = JSONObject(String(message.payload))
            val senderDeviceId = json.getString("deviceId")
            val text = json.getString("text")

            // Ignore own messages
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
        try {
            mqttClient?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT disconnected")
                    isConnected = false
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT disconnect failed", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect exception", e)
        }
    }
}
