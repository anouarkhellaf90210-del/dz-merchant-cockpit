package com.dzmerchant.cockpit

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.math.abs

class MerchantCockpitService : Service() {

    private var windowManager: WindowManager? = null
    private var cockpitView: MerchantCockpitOverlayView? = null
    private var storeFilter: String = "all"
    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var isStealthHidden = false
    private var lastKnownNewOrdersCount = -1
    private var lastKnownTopNewOrderId: String = ""
    private var loopbackServer: ServerSocket? = null
    @Volatile private var isServiceAlive = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isScreenOn = false
            } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                isScreenOn = true
                triggerSync(forceOpen = false)
            }
        }
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                triggerSync(forceOpen = false)
            }
            handler.postDelayed(this, 4000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("store")?.let {
            if (it.isNotBlank()) storeFilter = it
        }
        isStealthHidden = false
        cockpitView?.visibility = View.VISIBLE
        triggerSync(forceOpen = false)
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = resources.displayMetrics
        val bubblePx = (58 * metrics.density).toInt()
        val defaultRightX = (metrics.widthPixels - bubblePx - 24).coerceAtLeast(20)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultRightX
            y = 140
        }

        cockpitView = MerchantCockpitOverlayView(
            context = this,
            onPlayChime = { playOrderArrivalChime() },
            onHideStealth = {
                isStealthHidden = true
                cockpitView?.visibility = View.GONE
                Toast.makeText(
                    this,
                    "تم إخفاء كبسولة التاجر 🙈 • ستنبثق تلقائياً فور وصول طلبية جديدة!",
                    Toast.LENGTH_LONG
                ).show()
            },
            onCloseService = { stopSelf() },
            onOrderAction = { orderId, nextStatus ->
                Thread {
                    val ok = CockpitApiClient.updateOrderStatus(orderId, nextStatus)
                    if (ok) {
                        CockpitApiClient.notifyLocalCustomerCapsuleRefresh()
                        handler.post {
                            playOrderArrivalChime()
                            Toast.makeText(
                                this,
                                "✅ تم تحديث حالة الطرد وإشعار رادار الزبون فوراً",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        triggerSync(forceOpen = false)
                    } else {
                        handler.post {
                            Toast.makeText(
                                this,
                                "⚠️ تعذر تحديث الحالة، يرجى المحاولة مرة أخرى",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            },
            onToggleStore = { storeId, nextOpen ->
                Thread {
                    val ok = CockpitApiClient.toggleStoreStatus(storeId, nextOpen)
                    if (ok) triggerSync(forceOpen = false)
                }.start()
            },
            onSelectStore = { slug ->
                storeFilter = slug
                triggerSync(forceOpen = false)
            }
        )

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        cockpitView?.bubbleWrapper?.setOnTouchListener { _, event ->
            val curMetrics = resources.displayMetrics
            val screenW = curMetrics.widthPixels
            val screenH = curMetrics.heightPixels
            val bubbleSize = (58 * curMetrics.density).toInt()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    triggerSync(forceOpen = false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()
                    params.x = newX.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
                    params.y = newY.coerceIn(0, (screenH - bubbleSize).coerceAtLeast(0))
                    windowManager?.updateViewLayout(cockpitView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - initialTouchX)
                    val diffY = abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        val open = cockpitView?.isCockpitOpen() ?: false
                        cockpitView?.toggleCockpit(!open)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(cockpitView, params)

        val filter = IntentFilter().apply {
            addAction(Intent.ACT_SCREEN_ON_SAFE())
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        startLocalWakeupServer()
        handler.post(syncRunnable)
    }

    private fun Intent.Companion.ACT_SCREEN_ON_SAFE(): String = Intent.ACTION_SCREEN_ON

    private fun startLocalWakeupServer() {
        Thread {
            try {
                val server = ServerSocket(49211, 10, InetAddress.getByName("127.0.0.1"))
                loopbackServer = server
                while (isServiceAlive) {
                    val client = server.accept()
                    Thread {
                        try {
                            client.soTimeout = 1500
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val reqLine = reader.readLine() ?: ""
                            var line: String? = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                line = reader.readLine()
                            }

                            val shouldForceOpen = reqLine.contains("event=new_order") || reqLine.contains("open=1")
                            triggerSync(forceOpen = shouldForceOpen)

                            val out: OutputStream = client.getOutputStream()
                            val body = "{\"ok\":true,\"app\":\"dz-merchant-cockpit\"}"
                            val resp = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n" + body
                            out.write(resp.toByteArray(Charsets.UTF_8))
                            out.flush()
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun playOrderArrivalChime() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            handler.postDelayed({
                try {
                    toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 400)
                } catch (_: Exception) {}
            }, 380)

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 180), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(300)
            }
        } catch (_: Exception) {}
    }

    private fun triggerSync(forceOpen: Boolean) {
        Thread {
            val payload = CockpitApiClient.fetchCockpit(storeFilter)
            if (payload != null) {
                val topNewOrder = payload.orders.firstOrNull { it.stage == "new" }
                val topNewId = topNewOrder?.id ?: ""

                val hasBrandNewOrder =
                    (lastKnownNewOrdersCount != -1 && payload.newCount > lastKnownNewOrdersCount) ||
                    (lastKnownTopNewOrderId.isNotEmpty() && topNewId.isNotEmpty() && topNewId != lastKnownTopNewOrderId && payload.newCount >= lastKnownNewOrdersCount)

                if (hasBrandNewOrder || forceOpen) {
                    handler.post {
                        isStealthHidden = false
                        cockpitView?.visibility = View.VISIBLE
                        cockpitView?.toggleCockpit(true)
                        if (hasBrandNewOrder) {
                            playOrderArrivalChime()
                            Toast.makeText(
                                this,
                                "👑 طلبية جديدة وصلت المتجر الآن!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                lastKnownNewOrdersCount = payload.newCount
                if (topNewId.isNotEmpty()) {
                    lastKnownTopNewOrderId = topNewId
                }
                cockpitView?.updatePayload(payload)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceAlive = false
        handler.removeCallbacks(syncRunnable)
        try { loopbackServer?.close() } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (cockpitView != null) {
            windowManager?.removeView(cockpitView)
        }
    }
}
