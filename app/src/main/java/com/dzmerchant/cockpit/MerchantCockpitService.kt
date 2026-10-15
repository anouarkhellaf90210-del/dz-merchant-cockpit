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
import kotlin.math.abs

class MerchantCockpitService : Service() {

    private var windowManager: WindowManager? = null
    private var cockpitView: MerchantCockpitOverlayView? = null
    private var storeFilter: String = "all"
    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var isStealthHidden = false
    private var lastKnownNewOrdersCount = -1

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isScreenOn = false
            } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                isScreenOn = true
                triggerSync()
            }
        }
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                triggerSync()
            }
            handler.postDelayed(this, 15000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("store")?.let {
            if (it.isNotBlank()) storeFilter = it
        }
        // عند استدعاء التطبيق يدوياً، نلغي وضع الإخفاء ونظهر الكبسولة فوراً
        isStealthHidden = false
        cockpitView?.visibility = View.VISIBLE
        triggerSync()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 160
        }

        cockpitView = MerchantCockpitOverlayView(
            context = this,
            onPlayChime = { playOrderArrivalChime() },
            onHideStealth = {
                isStealthHidden = true
                cockpitView?.visibility = View.GONE
                Toast.makeText(
                    this,
                    "تم إخفاء الكبسولة 🙈 • ستنبثق تلقائياً فور وصول طلب جديد!",
                    Toast.LENGTH_LONG
                ).show()
            },
            onCloseService = { stopSelf() },
            onOrderAction = { orderId, nextStatus ->
                Thread {
                    val ok = CockpitApiClient.updateOrderStatus(orderId, nextStatus)
                    if (ok) {
                        playOrderArrivalChime()
                        triggerSync()
                    }
                }.start()
            },
            onToggleStore = { storeId, nextOpen ->
                Thread {
                    val ok = CockpitApiClient.toggleStoreStatus(storeId, nextOpen)
                    if (ok) triggerSync()
                }.start()
            },
            onSelectStore = { slug ->
                storeFilter = slug
                triggerSync()
            }
        )

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        cockpitView?.bubbleWrapper?.setOnTouchListener { _, event ->
            val metrics = resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val bubbleSize = (58 * metrics.density).toInt()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    triggerSync()
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
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        handler.post(syncRunnable)
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

    private fun triggerSync() {
        Thread {
            val payload = CockpitApiClient.fetchCockpit(storeFilter)
            if (payload != null) {
                // إذا وصلت طلبية جديدة أثناء الاختفاء الشبح -> تنبثق الكبسولة تلقائياً مع رنين!
                if (lastKnownNewOrdersCount != -1 && payload.newCount > lastKnownNewOrdersCount) {
                    handler.post {
                        isStealthHidden = false
                        cockpitView?.visibility = View.VISIBLE
                        cockpitView?.toggleCockpit(true)
                        playOrderArrivalChime()
                    }
                }
                lastKnownNewOrdersCount = payload.newCount
                cockpitView?.updatePayload(payload)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(syncRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        if (cockpitView != null) {
            windowManager?.removeView(cockpitView)
        }
    }
}
