package com.dzmerchant.cockpit

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DzFcmMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerDeviceToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            val slug = message.data["store_slug"] ?: ""
            val srvIntent = Intent(this, MerchantCockpitService::class.java).apply {
                if (slug.isNotBlank()) putExtra("store", slug)
                putExtra("expand", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(srvIntent)
            } else {
                startService(srvIntent)
            }
        } catch (_: Exception) {}
    }

    companion object {
        fun syncCurrentToken(context: Context) {
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { tok ->
                    if (!tok.isNullOrBlank()) registerDeviceToken(context, tok)
                }
            } catch (_: Exception) {}
        }

        fun registerDeviceToken(context: Context, fcmToken: String) {
            Thread {
                try {
                    val prefs = context.getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
                    val slug = prefs.getString("store_slug", "latest") ?: "latest"
                    val payload = JSONObject().apply {
                        put("device_token", fcmToken)
                        put("role", "merchant")
                        put("store_slug", slug)
                        put("tracking_token", "merchant")
                    }
                    val conn = (URL("https://elitemarketdz.top/api/push/fcm-token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {}
            }.start()
        }
    }
}
