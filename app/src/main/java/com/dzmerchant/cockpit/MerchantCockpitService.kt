package com.dzmerchant.cockpit

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class MerchantCockpitService : Service() {

    private var windowManager: WindowManager? = null
    private var cockpitView: MerchantCockpitOverlayView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var storeFilter: String = "all"
    private val handler = Handler(Looper.getMainLooper())
    private var isStealthHidden = false
    private var lastKnownNewOrdersCount = -1
    private var lastKnownTopOrderId: String = ""
    private val seenOrderIds = mutableSetOf<String>()
    private var isFirstSync = true
    private var loopbackServer: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeCustomRingtone: Ringtone? = null
    @Volatile private var isServiceAlive = true
    private var bgScheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var screenStateReceiver: android.content.BroadcastReceiver? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            triggerSync(forceOpen = false)
            handler.postDelayed(this, 2500)
        }
    }

    
    private fun ensureForegroundGuardian() {
        try {
            val channelId = "dz_merchant_cockpit_guardian"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                if (nm.getNotificationChannel(channelId) == null) {
                    val chan = android.app.NotificationChannel(
                        channelId,
                        "حارس كبسولة قيادة التاجر",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "يبقي كبسولة التاجر متصلة في الخلفية لرصد الطلبيات الجديدة حتى والشاشة مغلقة"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(chan)
                }
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                data = android.net.Uri.parse("dzmerchant://cockpit?open=1")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingOpen = android.app.PendingIntent.getActivity(this, 101, openIntent, piFlags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }

            val notification = builder
                .setContentTitle("👑 كبسولة قيادة التاجر نشطة بالخلفية")
                .setContentText("ترصد الطلبيات الحية 24/7 وتوقظ الهاتف فور وصول أي طلبية")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    49211,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(49211, notification)
            }
        } catch (_: Exception) {}
    }

    private fun fireUrgentOrderNotification(ord: MerchantOrderItem?, storeTitle: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val alertChannelId = "dz_merchant_urgent_order_alerts_v2"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(alertChannelId) == null) {
                    val chan = android.app.NotificationChannel(
                        alertChannelId,
                        "تنبيهات الطلبيات الجديدة الفورية 👑",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "إشعارات صوتية وبصرية فورية فوق شاشة القفل عند وصول طلبية جديدة"
                        enableLights(true)
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 500)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(chan)
                }
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                data = android.net.Uri.parse("dzmerchant://cockpit?event=new_order&open=1")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000).toInt(),
                openIntent,
                piFlags
            )

            val cleanStore = storeTitle.ifBlank { "المتجر" }
            val titleText = if (ord != null) {
                "🛍️ طلبية جديدة في " + cleanStore + " • " + ord.total + " د.ج"
            } else {
                "👑 طلبية جديدة وصلت " + cleanStore + " الآن!"
            }

            val bodyText = if (ord != null) {
                "👤 " + ord.customerName + " | 📍 " + ord.wilaya + " | 📦 " + ord.itemsSummary
            } else {
                "اضغط هنا لفتح كبسولة التاجر وتأكيد الطلبية فوراً"
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, alertChannelId)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
                    .setPriority(android.app.Notification.PRIORITY_MAX)
                    .setDefaults(android.app.Notification.DEFAULT_ALL)
            }

            builder.setContentTitle(titleText)
                .setContentText(bodyText)
                .setStyle(android.app.Notification.BigTextStyle().bigText(bodyText))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(android.app.Notification.CATEGORY_CALL)
                builder.setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            }

            nm.notify((System.currentTimeMillis() % 100000).toInt() + 50000, builder.build())
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundGuardian()
        intent?.getStringExtra("store")?.let {
            if (it.isNotBlank()) storeFilter = it
        }
        val forceOpen = intent?.getBooleanExtra("force_open", false) ?: false
        val playChimeNow = intent?.getBooleanExtra("play_chime", false) ?: false
        exitStealthOnMainThread(openCard = forceOpen)
        if (playChimeNow) {
            cockpitView?.refreshRingtoneLabel()
            playOrderArrivalChime()
        }
        triggerSync(forceOpen = forceOpen)
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        ensureForegroundGuardian()

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dzmerchant:CockpitWatchdog")?.apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
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
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

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
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        windowParams = params

        cockpitView = MerchantCockpitOverlayView(
            context = this,
            onPlayChime = { playOrderArrivalChime() },
            onPickPhoneRingtone = {
                try {
                    cockpitView?.toggleCockpit(false)
                    val pickIntent = Intent(this, MainActivity::class.java).apply {
                        action = "ACTION_PICK_RINGTONE"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(pickIntent)
                } catch (_: Exception) {}
            },
            onSetFocusable = { focusable ->
                setWindowFocusable(focusable)
            },
            onHideStealth = {
                setWindowFocusable(false)
                enterStealthOnMainThread(showToast = true)
            },
            onCloseService = {
                setWindowFocusable(false)
                enterStealthOnMainThread(showToast = false)
            },
            onOrderAction = { orderId, nextStatus ->
                playOrderArrivalChime()
                Toast.makeText(
                    this,
                    "⚡ تم تحديث حالة الطرد وإشعار رادار الزبون فوراً!",
                    Toast.LENGTH_SHORT
                ).show()
                Thread {
                    CockpitApiClient.notifyLocalCustomerCapsuleRefresh(nextStatus)
                    CockpitApiClient.updateOrderStatus(orderId, nextStatus)
                }.start()
            },
            onSaveOrderDetails = { updatedOrder ->
                Toast.makeText(
                    this,
                    "💾 جاري حفظ تعديلات الطلبية ${updatedOrder.ref}...",
                    Toast.LENGTH_SHORT
                ).show()
                Thread {
                    val ok = CockpitApiClient.updateOrderDetails(updatedOrder)
                    handler.post {
                        if (ok) {
                            Toast.makeText(
                                this,
                                "✅ تم حفظ تفاصيل الطلبية ${updatedOrder.ref} بنجاح!",
                                Toast.LENGTH_SHORT
                            ).show()
                            triggerSync(forceOpen = false)
                        } else {
                            Toast.makeText(
                                this,
                                "⚠️ تعذر حفظ التعديلات، تحقق من الاتصال",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
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
            if (isStealthHidden) return@setOnTouchListener false
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
                    try {
                        windowManager?.updateViewLayout(cockpitView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - initialTouchX)
                    val diffY = abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        val open = cockpitView?.isCockpitOpen() ?: false
                        if (open) setWindowFocusable(false)
                        cockpitView?.toggleCockpit(!open)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(cockpitView, params)
        } catch (_: Exception) {}

        startLocalWakeupServer()
        handler.post(syncRunnable)

        bgScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        bgScheduler?.scheduleAtFixedRate({
            try {
                if (isServiceAlive) {
                    triggerSync(forceOpen = false)
                }
            } catch (_: Exception) {}
        }, 3, 4, java.util.concurrent.TimeUnit.SECONDS)

        try {
            screenStateReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                        triggerSync(forceOpen = false)
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun setWindowFocusable(focusable: Boolean) {
        handler.post {
            val p = windowParams ?: return@post
            val newFlags = if (focusable) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (p.flags != newFlags) {
                p.flags = newFlags
                try {
                    windowManager?.updateViewLayout(cockpitView, p)
                } catch (_: Exception) {}
            }
        }
    }

    private fun enterStealthOnMainThread(showToast: Boolean) {
        handler.post {
            isStealthHidden = true
            cockpitView?.enterOpticalStealthMode()
            windowParams?.let { p ->
                p.flags = (p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                p.width = 1
                p.height = 1
                try {
                    windowManager?.updateViewLayout(cockpitView, p)
                } catch (_: Exception) {}
            }
            if (showToast) {
                Toast.makeText(
                    this,
                    "تم إخفاء كبسولة التاجر 🙈 • ستنبثق تلقائياً فور رصد أي طلبية جديدة!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exitStealthOnMainThread(openCard: Boolean) {
        handler.post {
            isStealthHidden = false
            windowParams?.let { p ->
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                p.width = WindowManager.LayoutParams.WRAP_CONTENT
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
                try {
                    windowManager?.updateViewLayout(cockpitView, p)
                } catch (_: Exception) {}
            }
            if (openCard) {
                cockpitView?.exitOpticalStealthAndOpen()
            } else {
                cockpitView?.exitOpticalStealthAndOpen()
                cockpitView?.toggleCockpit(false)
            }
        }
    }

    private fun wakeUpScreenAndPopup() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "dzmerchant:OrderAlertWakeup"
            )
            screenLock.acquire(10000)
        } catch (_: Exception) {}

        exitStealthOnMainThread(openCard = true)
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
            val prefs = getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString("ringtone_mode", "cashier_gold") ?: "cashier_gold"

            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 80, 200), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(350)
            }

            if (mode == "silent_vibrate") return

            if (mode == "custom_uri") {
                val uriStr = prefs.getString("custom_ringtone_uri", null)
                if (!uriStr.isNullOrBlank()) {
                    try {
                        activeCustomRingtone?.stop()
                        val ringtone = RingtoneManager.getRingtone(this, Uri.parse(uriStr))
                        activeCustomRingtone = ringtone
                        ringtone?.play()
                        handler.postDelayed({
                            try { ringtone?.stop() } catch (_: Exception) {}
                        }, 4000)
                        return
                    } catch (_: Exception) {}
                }
            }

            if (mode == "classic_bell") {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                handler.postDelayed({
                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 400)
                    } catch (_: Exception) {}
                }, 380)
                return
            }

            Thread {
                try {
                    val notes: List<Pair<Double, Int>> = when (mode) {
                        "radar_urgent" -> listOf(
                            880.0 to 110,
                            1174.66 to 110,
                            880.0 to 110,
                            1318.51 to 220
                        )
                        "royal_chime" -> listOf(
                            523.25 to 150,
                            659.25 to 150,
                            783.99 to 170,
                            1046.50 to 280
                        )
                        else -> listOf( // cashier_gold
                            587.33 to 120,
                            880.0 to 140,
                            1174.66 to 260
                        )
                    }

                    val sampleRate = 22050
                    val totalDurMs = notes.sumOf { it.second + 25 }
                    val totalSamples = (sampleRate * (totalDurMs / 1000.0)).toInt()
                    val pcm = ShortArray(totalSamples)
                    var idx = 0

                    for ((freq, durMs) in notes) {
                        val count = (sampleRate * (durMs / 1000.0)).toInt()
                        for (i in 0 until count) {
                            if (idx >= totalSamples) break
                            val env = 1.0 - (i.toDouble() / count.toDouble()) * 0.65
                            val s = sin(2.0 * Math.PI * i * freq / sampleRate)
                            pcm[idx++] = (s * Short.MAX_VALUE * 0.45 * env).toInt().toShort()
                        }
                        idx = min(totalSamples, idx + (sampleRate * 0.025).toInt())
                    }

                    val track = AudioTrack(
                        AudioManager.STREAM_NOTIFICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        pcm.size * 2,
                        AudioTrack.MODE_STATIC
                    )
                    track.write(pcm, 0, pcm.size)
                    track.play()
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    private fun triggerSync(forceOpen: Boolean) {
        Thread {
            val payload = CockpitApiClient.fetchCockpit(storeFilter)
            if (payload != null) {
                val currentTopNewOrder = payload.orders.firstOrNull { it.stage == "new" }
                val currentTopId = currentTopNewOrder?.id ?: ""

                var hasNewIncomingOrder = false
                if (!isFirstSync) {
                    if (lastKnownNewOrdersCount != -1 && payload.newCount > lastKnownNewOrdersCount) {
                        hasNewIncomingOrder = true
                    }
                    if (currentTopId.isNotEmpty() && currentTopId != lastKnownTopOrderId && !seenOrderIds.contains(currentTopId)) {
                        hasNewIncomingOrder = true
                    }
                    for (o in payload.orders) {
                        if (o.stage == "new" && !seenOrderIds.contains(o.id)) {
                            hasNewIncomingOrder = true
                            break
                        }
                    }
                }

                for (o in payload.orders) {
                    seenOrderIds.add(o.id)
                }
                isFirstSync = false
                lastKnownNewOrdersCount = payload.newCount
                if (currentTopId.isNotEmpty()) {
                    lastKnownTopOrderId = currentTopId
                }

                cockpitView?.updatePayload(payload)

                if (hasNewIncomingOrder || forceOpen) {
                    val alertOrd = currentTopNewOrder ?: payload.orders.firstOrNull()
                    handler.post {
                        if (hasNewIncomingOrder) {
                            fireUrgentOrderNotification(alertOrd, payload.storeName)
                        }
                        wakeUpScreenAndPopup()
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceAlive = false
        try { bgScheduler?.shutdownNow() } catch (_: Exception) {}
        try { if (screenStateReceiver != null) unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(syncRunnable)
        try { activeCustomRingtone?.stop() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        try { loopbackServer?.close() } catch (_: Exception) {}
        if (cockpitView != null) {
            try { windowManager?.removeView(cockpitView) } catch (_: Exception) {}
        }
    }
}
