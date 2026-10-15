package com.dzmerchant.cockpit

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var isStealthHidden = false
    private val seenOrderIds = mutableSetOf<String>()
    private var isFirstSync = true
    private var loopbackServer: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var isServiceAlive = true

    private val syncRunnable = object : Runnable {
        override fun run() {
            triggerSync(forceOpen = false)
            handler.postDelayed(this, 3000)
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

    @SuppressLint("ClickableViewAccessibility", "WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dzmerchant:CockpitWatchdog")
            wakeLock?.acquire()
        } catch (_: Exception) {}

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

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            flags,
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
                    "تم إخفاء كبسولة التاجر 🙈 • ستنبثق تلقائياً كالمسنجر فور وصول طلبية جديدة!",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCloseService = {
                isStealthHidden = true
                cockpitView?.visibility = View.GONE
            },
            onOrderAction = { orderId, nextStatus ->
                playOrderArrivalChime()
                Toast.makeText(
                    this,
                    "⚡ تم تحديث حالة الطلب وإشعار رادار الزبون فوراً!",
                    Toast.LENGTH_SHORT
                ).show()
                Thread {
                    CockpitApiClient.notifyLocalCustomerCapsuleRefresh(nextStatus)
                    CockpitApiClient.updateOrderStatus(orderId, nextStatus)
                }.start()
            },
            onToggleStore = { storeId, nextOpen ->
                Thread {
                    CockpitApiClient.toggleStoreStatus(storeId, nextOpen)
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
        startLocalWakeupServer()
        handler.post(syncRunnable)
    }

    private fun wakeUpScreenAndPopup() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "dzmerchant:OrderAlertWakeup"
            )
            screenLock.acquire(5000)
        } catch (_: Exception) {}

        isStealthHidden = false
        cockpitView?.visibility = View.VISIBLE
        cockpitView?.focusNewOrdersTab()
        cockpitView?.toggleCockpit(true)
        playOrderArrivalChime()
        Toast.makeText(
            this,
            "👑 طلبية جديدة وصلت المتجر الآن!",
            Toast.LENGTH_LONG
        ).show()
    }

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
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                            val reqLine = reader.readLine() ?: ""
                            var line: String? = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                line = reader.readLine()
                            }

                            val shouldForceOpen = reqLine.contains("event=new_order") || reqLine.contains("open=1")
                            if (shouldForceOpen) {
                                storeFilter = "all"
                                handler.post { wakeUpScreenAndPopup() }
                            }
                            triggerSync(forceOpen = shouldForceOpen)

                            val out: OutputStream = client.getOutputStream()
                            val body = "{\"ok\":true,\"app\":\"dz-merchant-cockpit\"}"
                            val resp = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Content-Length: " + body.toByteArray(Charsets.UTF_8).size + "\r\n" +
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
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 80, 200), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(350)
            }
        } catch (_: Exception) {}
    }

    private fun triggerSync(forceOpen: Boolean) {
        Thread {
            val payload = CockpitApiClient.fetchCockpit(storeFilter)
            if (payload != null) {
                var discoveredNewOrder = false
                for (o in payload.orders) {
                    if (o.stage == "new" && !seenOrderIds.contains(o.id)) {
                        if (!isFirstSync) {
                            discoveredNewOrder = true
                        }
                        seenOrderIds.add(o.id)
                    } else {
                        seenOrderIds.add(o.id)
                    }
                }
                isFirstSync = false

                cockpitView?.updatePayload(payload)

                if (discoveredNewOrder || forceOpen) {
                    handler.post { wakeUpScreenAndPopup() }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceAlive = false
        handler.removeCallbacks(syncRunnable)
        try { wakeLock?.release() } catch (_: Exception) {}
        try { loopbackServer?.close() } catch (_: Exception) {}
        if (cockpitView != null) {
            windowManager?.removeView(cockpitView)
        }
    }
}
