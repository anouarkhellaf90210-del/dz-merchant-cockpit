package com.dzmerchant.cockpit

import android.animation.ValueAnimator
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

open class MerchantCockpitService : Service() {

    companion object {
        private const val CHANNEL_ID = "dz_merchant_cockpit_channel_v3"
        private const val NOTIFICATION_ID = 9211
        private const val LOCAL_BRIDGE_PORT = 49211
        private const val POLL_INTERVAL_MS = 10000L
        private const val PREFS_NAME = "dz_merchant_cockpit_prefs"
    }

    data class OrderItemModel(
        val id: String,
        val productId: String?,
        val variantId: String?,
        var name: String,
        var variant: String,
        var quantity: Int,
        var unitPrice: Double
    ) {
        val totalPrice: Double
            get() = unitPrice * quantity
    }

    data class OrderModel(
        val id: String,
        val shortId: String,
        val trackingToken: String,
        var customerName: String,
        var customerPhone: String,
        var wilayaName: String,
        var communeName: String,
        var address: String,
        val deliveryType: String,
        var status: String,
        var subtotalPrice: Double,
        var shippingCost: Double,
        var totalPrice: Double,
        var notes: String,
        val createdAt: String,
        val createdAtMs: Long,
        var itemsCount: Int,
        var itemsSummary: String,
        val items: MutableList<OrderItemModel>
    )

    data class PeriodComparisonStats(
        val periodTitle: String,
        val dateRangeLabel: String,
        val currentSales: Double,
        val currentOrdersCount: Int,
        val previousSales: Double,
        val previousOrdersCount: Int,
        val previousLabel: String
    )

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newCachedThreadPool()

    private var rootBubbleContainer: FrameLayout? = null
    private var collapsedPillView: LinearLayout? = null
    private var pillBadgeText: TextView? = null
    private var pillStatusDot: View? = null

    private var cockpitModalOverlay: FrameLayout? = null
    private var cockpitHeaderStoreTitle: TextView? = null
    private var cockpitSyncSubtitle: TextView? = null

    private var periodTitleView: TextView? = null
    private var periodSalesValueText: TextView? = null
    private var periodOrdersSubText: TextView? = null
    private var periodDeltaBadgeText: TextView? = null
    private var currentPeriodBarView: View? = null
    private var previousPeriodBarView: View? = null
    private var periodNavTitleText: TextView? = null
    private var nextOffsetBtnView: TextView? = null
    private var resetTodayBtnView: TextView? = null

    private val timeRangeChipMap = mutableMapOf<String, TextView>()
    private var selectedTimeRange: String = "day"
    private var selectedDateOffset: Int = 0

    private var ringtoneToggleBtn: TextView? = null
    private var ringtoneSelectorPanel: LinearLayout? = null
    private var isRingtonePanelOpen = false
    private var activeCustomRingtone: Ringtone? = null

    private var ordersListLayout: LinearLayout? = null
    private val filterChipViews = mutableMapOf<String, TextView>()

    private var isCockpitExpanded = false
    private var activeFilter = "pending"
    private var storeName = "متجري"
    private var allOrders = mutableListOf<OrderModel>()
    private val knownOrderIds = mutableSetOf<String>()
    private var isFirstFetchCompleted = false

    private val expandedOrderDetailsIds = mutableSetOf<String>()
    private val editingCustomerOrderIds = mutableSetOf<String>()

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var isServiceRunning = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return
            fetchMerchantOrders(manualTrigger = false)
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedTimeRange = prefs.getString("selected_time_range", "day") ?: "day"
        selectedDateOffset = prefs.getInt("selected_date_offset", 0)

        startForegroundNotification()
        buildFloatingOverlayUI()
        startLocalBridgeServer()
        mainHandler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_OR_SYNC" -> fetchMerchantOrders(manualTrigger = true)
            "ACTION_RELOAD_RINGTONE" -> {
                updateRingtoneButtonLabel()
                playOrderArrivalChime(previewOnly = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            activeCustomRingtone?.stop()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            if (rootBubbleContainer != null) windowManager.removeView(rootBubbleContainer)
        } catch (_: Exception) {}
        try {
            if (cockpitModalOverlay != null) windowManager.removeView(cockpitModalOverlay)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "كبسولة القيادة الفورية للتاجر",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مراقبة وتأكيد طلبيات المتجر لحظياً"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("👑 كبسولة التاجر نشطة")
            .setContentText("تأكيد الطلبيات ومقارنة المبيعات اليومية بالوقت الفعلي")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateWindowFocusable(focusable: Boolean) {
        val modal = cockpitModalOverlay ?: return
        val lp = modal.layoutParams as? WindowManager.LayoutParams ?: return
        val newFlags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        if (lp.flags != newFlags) {
            lp.flags = newFlags
            try {
                windowManager.updateViewLayout(modal, lp)
            } catch (_: Exception) {}
        }
    }

    private fun buildFloatingOverlayUI() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        rootBubbleContainer = FrameLayout(this)

        collapsedPillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#0F172A"),
                    Color.parseColor("#1E1B4B")
                )
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
            elevation = dp(10).toFloat()
        }

        pillStatusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(7)
            }
        }
        collapsedPillView?.addView(pillStatusDot)

        val crownText = TextView(this).apply {
            text = "👑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
        }
        collapsedPillView?.addView(crownText)

        pillBadgeText = TextView(this).apply {
            text = "التاجر"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
        }
        collapsedPillView?.addView(pillBadgeText)

        rootBubbleContainer?.addView(collapsedPillView)
        attachSpringDragAndTapListener(rootBubbleContainer!!)
        windowManager.addView(rootBubbleContainer, bubbleParams)

        buildCockpitModalWindow(overlayType)
    }

    private fun buildCockpitModalWindow(overlayType: Int) {
        val modalParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        cockpitModalOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#B804070E"))
            visibility = View.GONE
            setOnClickListener { collapseCockpit() }
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val cardMaxHeight = (screenHeight * 0.90f).toInt()

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#0B1120"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                cardMaxHeight
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(10)
                rightMargin = dp(10)
            }
            setOnClickListener { /* منع إغلاق الكبسولة عند الضغط داخلها */ }
        }

        // 1. شريط الرأس العلوي + زر النغمة + التحديث + الإغلاق
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        cockpitHeaderStoreTitle = TextView(this).apply {
            text = "👑 كبسولة التاجر • متجري"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(null, Typeface.BOLD)
        }
        titleCol.addView(cockpitHeaderStoreTitle)

        cockpitSyncSubtitle = TextView(this).apply {
            text = "متصل بالخادم الحي • تحديث تلقائي"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }
        titleCol.addView(cockpitSyncSubtitle)
        headerRow.addView(titleCol)

        // زر تخصيص نغمة الرنين
        ringtoneToggleBtn = TextView(this).apply {
            text = "🎵 النغمة ▾"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                isRingtonePanelOpen = !isRingtonePanelOpen
                ringtoneSelectorPanel?.visibility = if (isRingtonePanelOpen) View.VISIBLE else View.GONE
                updateRingtoneButtonLabel()
            }
        }
        headerRow.addView(ringtoneToggleBtn)

        val syncBtn = TextView(this).apply {
            text = "🔄"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                cockpitSyncSubtitle?.text = "⏳ جاري المزامنة الفورية..."
                fetchMerchantOrders(manualTrigger = true)
            }
        }
        headerRow.addView(syncBtn)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#311018"))
            }
            setOnClickListener { collapseCockpit() }
        }
        headerRow.addView(closeBtn)
        cardContainer.addView(headerRow)

        // 2. لوحة تخصيص نغمة الرنين القابلة للطي
        ringtoneSelectorPanel = buildRingtoneSelectorView()
        cardContainer.addView(ringtoneSelectorPanel)
        updateRingtoneButtonLabel()

        // 3. شريط محدد النطاق الزمني (يومي / أسبوعي / شهري / الكل) + أسهم الرجوع والمقارنة البصرية
        val timeSectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#131C31"),
                    Color.parseColor("#0F172A")
                )
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        // أزرار اختيار النطاق: يومي | أسبوعي | شهري | الكل
        val rangeTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(7)
            }
        }

        val rangeDefs = listOf(
            "day" to "📅 يومي",
            "week" to "📆 أسبوعي",
            "month" to "🗓️ شهري",
            "all" to "♾️ الكل"
        )

        for ((key, title) in rangeDefs) {
            val chip = TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    selectedTimeRange = key
                    selectedDateOffset = 0
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString("selected_time_range", selectedTimeRange)
                        .putInt("selected_date_offset", selectedDateOffset)
                        .apply()
                    renderCockpitState()
                }
            }
            timeRangeChipMap[key] = chip
            rangeTabsRow.addView(chip)
        }
        timeSectionCard.addView(rangeTabsRow)

        // شريط الأسهم للتنقل عبر الأيام / الأسابيع / الأشهر السابقة (البارحة، قبل البارحة...)
        val dateNavRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val prevOffsetBtn = TextView(this).apply {
            text = "◀ السابق"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            setOnClickListener {
                if (selectedTimeRange != "all") {
                    selectedDateOffset -= 1
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt("selected_date_offset", selectedDateOffset)
                        .apply()
                    renderCockpitState()
                }
            }
        }
        dateNavRow.addView(prevOffsetBtn)

        periodNavTitleText = TextView(this).apply {
            text = "اليوم"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateNavRow.addView(periodNavTitleText)

        resetTodayBtnView = TextView(this).apply {
            text = "↺ اليوم"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#0C4A6E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(5)
            }
            setOnClickListener {
                selectedDateOffset = 0
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt("selected_date_offset", 0)
                    .apply()
                renderCockpitState()
            }
        }
        dateNavRow.addView(resetTodayBtnView)

        nextOffsetBtnView = TextView(this).apply {
            text = "التالي ▶"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            setOnClickListener {
                if (selectedDateOffset < 0) {
                    selectedDateOffset += 1
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt("selected_date_offset", selectedDateOffset)
                        .apply()
                    renderCockpitState()
                }
            }
        }
        dateNavRow.addView(nextOffsetBtnView)
        timeSectionCard.addView(dateNavRow)

        // أرقام المبيعات والمقارنة البصرية المباشرة
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val leftMetricCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        periodTitleView = TextView(this).apply {
            text = "💰 مبيعات اليوم"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
        }
        leftMetricCol.addView(periodTitleView)

        periodSalesValueText = TextView(this).apply {
            text = "0 د.ج"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(null, Typeface.BOLD)
        }
        leftMetricCol.addView(periodSalesValueText)

        periodOrdersSubText = TextView(this).apply {
            text = "0 طلبية مؤكدة/جديدة"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        leftMetricCol.addView(periodOrdersSubText)
        metricsRow.addView(leftMetricCol)

        periodDeltaBadgeText = TextView(this).apply {
            text = "مقارنة بالبارحة: 0 د.ج"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#064E3B"))
            }
        }
        metricsRow.addView(periodDeltaBadgeText)
        timeSectionCard.addView(metricsRow)

        // شريط المقارنة البصرية المزدوج (الفترة الحالية مقابل السابقة)
        val visualBarsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(7)
            }
        }

        currentPeriodBarView = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(6)).apply {
                bottomMargin = dp(4)
            }
        }
        visualBarsContainer.addView(currentPeriodBarView)

        previousPeriodBarView = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#64748B"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(5))
        }
        visualBarsContainer.addView(previousPeriodBarView)

        timeSectionCard.addView(visualBarsContainer)
        cardContainer.addView(timeSectionCard)

        // 4. شريط تبويبات حالات الطلبيات
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tabs = listOf(
            "pending" to "🔥 جديد",
            "confirmed" to "✅ تجهيز",
            "shipped" to "🚚 في الطريق",
            "delivered" to "🎉 تم التسليم",
            "all" to "📋 الكل"
        )

        for ((key, label) in tabs) {
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    activeFilter = key
                    renderCockpitState()
                }
            }
            filterChipViews[key] = chip
            filterRow.addView(chip)
        }

        filterScroll.addView(filterRow)
        cardContainer.addView(filterScroll)

        // 5. قائمة البطاقات القابلة للتمرير
        val ordersScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        ordersListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(14))
        }
        ordersScroll.addView(ordersListLayout)
        cardContainer.addView(ordersScroll)

        cockpitModalOverlay?.addView(cardContainer)
        windowManager.addView(cockpitModalOverlay, modalParams)
    }

    private fun buildRingtoneSelectorView(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#172033"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val header = TextView(this).apply {
            text = "🔔 تخصيص نغمة رنين وصول الطلبيات الجديدة (اضغط للتجربة والحفظ):"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
        panel.addView(header)

        val tones = listOf(
            "cashier_gold" to "💰 جرس الكاشير الذهبي (افتراضي)",
            "radar_urgent" to "🚨 رادار الطوارئ السريع (عالي التنبيه)",
            "royal_chime" to "👑 النغمة الملكية الفاخرة (هادئة)",
            "classic_bell" to "🔔 رنين المتجر الكلاسيكي",
            "custom_uri" to "📱 نغمة مخصصة من ملفات الهاتف...",
            "silent_vibrate" to "📳 اهتزاز صامت فقط بدون صوت"
        )

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        for ((modeKey, modeLabel) in tones) {
            val rowBtn = TextView(this).apply {
                val customTitle = prefs.getString("custom_ringtone_title", null)
                text = if (modeKey == "custom_uri" && !customTitle.isNullOrBlank()) {
                    "📱 نغمة الهاتف: $customTitle (اضغط لتغييرها)"
                } else {
                    modeLabel
                }
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#0F172A"))
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(5)
                }
                setOnClickListener {
                    if (modeKey == "custom_uri") {
                        collapseCockpit()
                        val pickIntent = Intent(this@MerchantCockpitService, MainActivity::class.java).apply {
                            action = "ACTION_PICK_RINGTONE"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(pickIntent)
                    } else {
                        prefs.edit().putString("ringtone_mode", modeKey).apply()
                        updateRingtoneButtonLabel()
                        playOrderArrivalChime(previewOnly = true)
                        Toast.makeText(
                            this@MerchantCockpitService,
                            "✅ تم اعتماد النغمة: $modeLabel",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            panel.addView(rowBtn)
        }

        return panel
    }

    private fun updateRingtoneButtonLabel() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString("ringtone_mode", "cashier_gold") ?: "cashier_gold"
        val shortName = when (mode) {
            "cashier_gold" -> "🎵 الكاشير"
            "radar_urgent" -> "🚨 رادار"
            "royal_chime" -> "👑 ملكي"
            "classic_bell" -> "🔔 كلاسيك"
            "custom_uri" -> "📱 نغمة الهاتف"
            "silent_vibrate" -> "📳 صامت"
            else -> "🎵 النغمة"
        }
        ringtoneToggleBtn?.text = if (isRingtonePanelOpen) "$shortName ▴" else "$shortName ▾"
    }

    private fun expandCockpit() {
        isCockpitExpanded = true
        cockpitModalOverlay?.visibility = View.VISIBLE
        updateWindowFocusable(editingCustomerOrderIds.isNotEmpty())
        renderCockpitState()
        fetchMerchantOrders(manualTrigger = false)
    }

    private fun collapseCockpit() {
        isCockpitExpanded = false
        isRingtonePanelOpen = false
        ringtoneSelectorPanel?.visibility = View.GONE
        editingCustomerOrderIds.clear()
        updateWindowFocusable(false)
        cockpitModalOverlay?.visibility = View.GONE
    }

    private fun attachSpringDragAndTapListener(targetView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        targetView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        bubbleParams.x = initialX + dx
                        bubbleParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(rootBubbleContainer, bubbleParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        if (isCockpitExpanded) collapseCockpit() else expandCockpit()
                    } else {
                        snapBubbleToNearestEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBubbleToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val pillWidth = rootBubbleContainer?.width ?: dp(110)
        val targetX = if (bubbleParams.x + pillWidth / 2 < screenWidth / 2) {
            dp(10)
        } else {
            screenWidth - pillWidth - dp(10)
        }

        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                bubbleParams.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(rootBubbleContainer, bubbleParams)
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun parseIsoTimestampMs(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val dt = sdf.parse(iso)
                if (dt != null) return dt.time
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    private fun computePeriodBounds(range: String, offset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return when (range) {
            "day" -> {
                cal.add(Calendar.DAY_OF_YEAR, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis
                start to end
            }
            "week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.add(Calendar.WEEK_OF_YEAR, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                val end = cal.timeInMillis
                start to end
            }
            "month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                val end = cal.timeInMillis
                start to end
            }
            else -> 0L to Long.MAX_VALUE
        }
    }

    private fun computePeriodStats(): PeriodComparisonStats {
        val dateFmt = SimpleDateFormat("yyyy/MM/dd", Locale("ar", "DZ"))
        if (selectedTimeRange == "all") {
            var totalSales = 0.0
            var totalCount = 0
            for (o in allOrders) {
                if (!isCancelledStatus(o.status)) {
                    totalSales += o.totalPrice
                    totalCount++
                }
            }
            return PeriodComparisonStats(
                periodTitle = "💰 إجمالي المبيعات الكلي",
                dateRangeLabel = "كل الفترات الزمنية",
                currentSales = totalSales,
                currentOrdersCount = totalCount,
                previousSales = totalSales,
                previousOrdersCount = totalCount,
                previousLabel = "الإجمالي التراكمي"
            )
        }

        val (curStart, curEnd) = computePeriodBounds(selectedTimeRange, selectedDateOffset)
        val (prevStart, prevEnd) = computePeriodBounds(selectedTimeRange, selectedDateOffset - 1)

        var curSales = 0.0
        var curCount = 0
        var prevSales = 0.0
        var prevCount = 0

        for (o in allOrders) {
            if (isCancelledStatus(o.status)) continue
            val ts = o.createdAtMs
            if (ts in curStart until curEnd) {
                curSales += o.totalPrice
                curCount++
            } else if (ts in prevStart until prevEnd) {
                prevSales += o.totalPrice
                prevCount++
            }
        }

        val dateStr = dateFmt.format(Date(curStart))
        val title: String
        val navLabel: String
        val prevLabel: String

        when (selectedTimeRange) {
            "day" -> {
                when (selectedDateOffset) {
                    0 -> {
                        title = "💰 مبيعات اليوم ($dateStr)"
                        navLabel = "اليوم • $dateStr"
                        prevLabel = "مقابل البارحة"
                    }
                    -1 -> {
                        title = "💰 مبيعات البارحة ($dateStr)"
                        navLabel = "البارحة • $dateStr"
                        prevLabel = "مقابل قبل البارحة"
                    }
                    -2 -> {
                        title = "💰 مبيعات قبل البارحة ($dateStr)"
                        navLabel = "قبل البارحة • $dateStr"
                        prevLabel = "مقابل اليوم الذي يسبقه"
                    }
                    else -> {
                        val daysAgo = abs(selectedDateOffset)
                        title = "💰 مبيعات منذ $daysAgo أيام ($dateStr)"
                        navLabel = "منذ $daysAgo أيام • $dateStr"
                        prevLabel = "مقابل اليوم السابق له"
                    }
                }
            }
            "week" -> {
                val endStr = dateFmt.format(Date(curEnd - 1000))
                title = if (selectedDateOffset == 0) "💰 مبيعات هذا الأسبوع" else "💰 مبيعات أسبوع سابق"
                navLabel = "$dateStr ⬅ $endStr"
                prevLabel = "مقابل الأسبوع السابق"
            }
            else -> {
                val monthFmt = SimpleDateFormat("MMMM yyyy", Locale("ar", "DZ"))
                val mStr = monthFmt.format(Date(curStart))
                title = "💰 مبيعات شهر $mStr"
                navLabel = mStr
                prevLabel = "مقابل الشهر السابق"
            }
        }

        return PeriodComparisonStats(
            periodTitle = title,
            dateRangeLabel = navLabel,
            currentSales = curSales,
            currentOrdersCount = curCount,
            previousSales = prevSales,
            previousOrdersCount = prevCount,
            previousLabel = prevLabel
        )
    }

    private fun isCancelledStatus(st: String): Boolean {
        return st == "cancelled" || st == "canceled" || st == "returned"
    }

    private fun startLocalBridgeServer() {
        networkExecutor.execute {
            try {
                serverSocket = ServerSocket(LOCAL_BRIDGE_PORT)
                while (isServiceRunning) {
                    val client = serverSocket?.accept() ?: break
                    networkExecutor.execute {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val requestLine = reader.readLine() ?: ""
                            if (requestLine.contains("/ping") || requestLine.contains("/sync")) {
                                fetchMerchantOrders(manualTrigger = true)
                            }
                            val responseJson =
                                "{\"ok\":true,\"app\":\"dz-merchant-cockpit\",\"version\":3}"
                            val httpResponse =
                                "HTTP/1.1 200 OK\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${responseJson.toByteArray().size}\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    responseJson
                            client.getOutputStream().write(httpResponse.toByteArray())
                            client.getOutputStream().flush()
                        } catch (_: Exception) {
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun fetchMerchantOrders(manualTrigger: Boolean) {
        networkExecutor.execute {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val baseUrl = (prefs.getString(
                    "base_url",
                    "https://my-private-store-app.vercel.app"
                ) ?: "https://my-private-store-app.vercel.app").trimEnd('/')
                val slug = (prefs.getString("store_slug", "") ?: "").trim()

                val endpoint = StringBuilder("$baseUrl/api/merchant/companion?limit=250")
                if (slug.isNotEmpty()) {
                    endpoint.append("&slug=").append(URLEncoder.encode(slug, "UTF-8"))
                }

                val conn = (URL(endpoint.toString()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 9000
                    readTimeout = 9000
                    setRequestProperty("Accept", "application/json")
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("ok", false)) {
                        val storeObj = json.optJSONObject("store")
                        storeName = storeObj?.optString("name", "متجري") ?: "متجري"

                        val ordersArr = json.optJSONArray("orders") ?: JSONArray()
                        val parsedOrders = mutableListOf<OrderModel>()
                        var newlyArrivedOrder = false

                        for (i in 0 until ordersArr.length()) {
                            val o = ordersArr.getJSONObject(i)
                            val oid = o.optString("id", "")
                            if (oid.isEmpty()) continue

                            val itemsArr = o.optJSONArray("items") ?: JSONArray()
                            val parsedItems = mutableListOf<OrderItemModel>()
                            for (j in 0 until itemsArr.length()) {
                                val itObj = itemsArr.getJSONObject(j)
                                parsedItems.add(
                                    OrderItemModel(
                                        id = itObj.optString("id", "item-$j"),
                                        productId = itObj.optString("product_id", "").takeIf { it.isNotEmpty() },
                                        variantId = itObj.optString("variant_id", "").takeIf { it.isNotEmpty() },
                                        name = itObj.optString("name", "منتج"),
                                        variant = itObj.optString("variant", ""),
                                        quantity = max(1, itObj.optInt("quantity", 1)),
                                        unitPrice = itObj.optDouble("unit_price", 0.0)
                                    )
                                )
                            }

                            val createdIso = o.optString("created_at", "")
                            val orderModel = OrderModel(
                                id = oid,
                                shortId = o.optString("short_id", oid.take(8).uppercase()),
                                trackingToken = o.optString("tracking_token", ""),
                                customerName = o.optString("customer_name", "زبون الكريم"),
                                customerPhone = o.optString("customer_phone", ""),
                                wilayaName = o.optString("wilaya_name", "الجزائر"),
                                communeName = o.optString("commune_name", ""),
                                address = o.optString("address", ""),
                                deliveryType = o.optString("delivery_type", "home"),
                                status = o.optString("status", "pending"),
                                subtotalPrice = o.optDouble("subtotal_price", 0.0),
                                shippingCost = o.optDouble("shipping_cost", 0.0),
                                totalPrice = o.optDouble("total_price", 0.0),
                                notes = o.optString("notes", ""),
                                createdAt = createdIso,
                                createdAtMs = parseIsoTimestampMs(createdIso),
                                itemsCount = o.optInt("items_count", 1),
                                itemsSummary = o.optString("items_summary", "طلبية جديدة"),
                                items = parsedItems
                            )
                            parsedOrders.add(orderModel)

                            if (isFirstFetchCompleted && !knownOrderIds.contains(oid)) {
                                newlyArrivedOrder = true
                            }
                            knownOrderIds.add(oid)
                        }

                        isFirstFetchCompleted = true
                        allOrders = parsedOrders

                        mainHandler.post {
                            if (newlyArrivedOrder) {
                                playOrderArrivalChime(previewOnly = false)
                                Toast.makeText(
                                    this@MerchantCockpitService,
                                    "🎉 وصلت طلبية جديدة إلى المتجر!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            renderCockpitState()
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                mainHandler.post {
                    if (manualTrigger) {
                        cockpitSyncSubtitle?.text = "⚠️ تعذر الاتصال بالخادم، تحقق من الإنترنت"
                    }
                }
            }
        }
    }

    private fun renderCockpitState() {
        val totalPendingCount = allOrders.count { matchesTabFilter(it.status, "pending") }

        // تحديث شارة الكبسولة العائمة المصغرة
        if (totalPendingCount > 0) {
            pillBadgeText?.text = "🔥 $totalPendingCount جديد"
            (pillStatusDot?.background as? GradientDrawable)?.setColor(Color.parseColor("#F59E0B"))
        } else {
            pillBadgeText?.text = "👑 ${allOrders.size} طلب"
            (pillStatusDot?.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
        }

        cockpitHeaderStoreTitle?.text = "👑 كبسولة التاجر • $storeName"
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        cockpitSyncSubtitle?.text = "آخر مزامنة حية: $timeFmt"

        // 1. تحديث أزرار النطاق الزمني
        for ((key, chip) in timeRangeChipMap) {
            val selected = (key == selectedTimeRange)
            chip.setTextColor(if (selected) Color.parseColor("#090D16") else Color.parseColor("#CBD5E1"))
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (selected) {
                    setColor(Color.parseColor("#F59E0B"))
                } else {
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
            }
        }

        // 2. حساب إحصائيات المبيعات والمقارنة البصرية للفترة المختارة
        val periodStats = computePeriodStats()
        periodTitleView?.text = periodStats.periodTitle
        periodNavTitleText?.text = periodStats.dateRangeLabel
        periodSalesValueText?.text = "${formatDzd(periodStats.currentSales)} د.ج"
        periodOrdersSubText?.text = "${periodStats.currentOrdersCount} طلبية في هذه الفترة"

        resetTodayBtnView?.visibility = if (selectedDateOffset != 0 && selectedTimeRange != "all") {
            View.VISIBLE
        } else {
            View.GONE
        }
        nextOffsetBtnView?.alpha = if (selectedDateOffset < 0 && selectedTimeRange != "all") 1.0f else 0.35f

        val diff = periodStats.currentSales - periodStats.previousSales
        val pct = if (periodStats.previousSales > 0) {
            ((diff / periodStats.previousSales) * 100.0).toInt()
        } else if (periodStats.currentSales > 0) {
            100
        } else {
            0
        }

        if (selectedTimeRange == "all") {
            periodDeltaBadgeText?.text = "📊 المجموع الكلي"
            periodDeltaBadgeText?.setTextColor(Color.parseColor("#38BDF8"))
            (periodDeltaBadgeText?.background as? GradientDrawable)?.setColor(Color.parseColor("#0C4A6E"))
        } else if (diff >= 0) {
            val sign = if (diff > 0) "+" else ""
            periodDeltaBadgeText?.text = "📈 $sign${formatDzd(diff)} د.ج ($sign$pct%)\n${periodStats.previousLabel}: ${formatDzd(periodStats.previousSales)} د.ج"
            periodDeltaBadgeText?.setTextColor(Color.parseColor("#34D399"))
            (periodDeltaBadgeText?.background as? GradientDrawable)?.setColor(Color.parseColor("#064E3B"))
        } else {
            periodDeltaBadgeText?.text = "📉 ${formatDzd(diff)} د.ج ($pct%)\n${periodStats.previousLabel}: ${formatDzd(periodStats.previousSales)} د.ج"
            periodDeltaBadgeText?.setTextColor(Color.parseColor("#FCA5A5"))
            (periodDeltaBadgeText?.background as? GradientDrawable)?.setColor(Color.parseColor("#450A0A"))
        }

        // تحديث عرض شريطي المقارنة البصرية
        val maxBarWidth = dp(240)
        val minBarWidth = dp(24)
        val maxSalesVal = max(1.0, max(periodStats.currentSales, periodStats.previousSales))
        val curBarW = max(minBarWidth, ((periodStats.currentSales / maxSalesVal) * maxBarWidth).toInt())
        val prevBarW = max(minBarWidth, ((periodStats.previousSales / maxSalesVal) * maxBarWidth).toInt())

        currentPeriodBarView?.layoutParams = (currentPeriodBarView?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = curBarW
        }
        previousPeriodBarView?.layoutParams = (previousPeriodBarView?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = prevBarW
        }
        currentPeriodBarView?.requestLayout()
        previousPeriodBarView?.requestLayout()

        // 3. فلترة الطلبيات حسب النطاق الزمني المختار
        val (boundStart, boundEnd) = computePeriodBounds(selectedTimeRange, selectedDateOffset)
        val timeFilteredOrders = if (selectedTimeRange == "all") {
            allOrders
        } else {
            allOrders.filter { it.createdAtMs in boundStart until boundEnd }
        }

        val cPending = timeFilteredOrders.count { matchesTabFilter(it.status, "pending") }
        val cConfirmed = timeFilteredOrders.count { matchesTabFilter(it.status, "confirmed") }
        val cShipped = timeFilteredOrders.count { matchesTabFilter(it.status, "shipped") }
        val cDelivered = timeFilteredOrders.count { matchesTabFilter(it.status, "delivered") }

        val tabLabels = mapOf(
            "pending" to "🔥 جديد ($cPending)",
            "confirmed" to "✅ تجهيز ($cConfirmed)",
            "shipped" to "🚚 في الطريق ($cShipped)",
            "delivered" to "🎉 تم التسليم ($cDelivered)",
            "all" to "📋 الكل (${timeFilteredOrders.size})"
        )

        for ((key, chip) in filterChipViews) {
            chip.text = tabLabels[key] ?: key
            val selected = (key == activeFilter)
            chip.setTextColor(if (selected) Color.parseColor("#090D16") else Color.parseColor("#CBD5E1"))
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) {
                    setColor(Color.parseColor("#F59E0B"))
                } else {
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
            }
        }

        val filteredOrders = timeFilteredOrders.filter { matchesTabFilter(it.status, activeFilter) }
        ordersListLayout?.removeAllViews()

        if (filteredOrders.isEmpty()) {
            val emptyBox = TextView(this).apply {
                text = "لا توجد طلبيات في هذا القسم ضمن الفترة المحددة (${periodStats.dateRangeLabel})"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(36), dp(20), dp(36))
            }
            ordersListLayout?.addView(emptyBox)
            return
        }

        for (order in filteredOrders) {
            ordersListLayout?.addView(buildOrderCardView(order))
        }
    }

    private fun matchesTabFilter(status: String, tab: String): Boolean {
        val st = status.lowercase()
        return when (tab) {
            "pending" -> st == "pending" || st == "new"
            "confirmed" -> st == "confirmed" || st == "processing" || st == "preparing"
            "shipped" -> st == "shipped" || st == "in_transit" || st == "out_for_delivery"
            "delivered" -> st == "delivered" || st == "completed"
            else -> true
        }
    }

    private fun recalculateOrderTotals(order: OrderModel) {
        val sub = order.items.sumOf { it.unitPrice * it.quantity }
        order.subtotalPrice = sub
        order.totalPrice = max(0.0, sub + order.shippingCost)
        order.itemsCount = order.items.sumOf { it.quantity }
        order.itemsSummary = order.items.joinToString(" ، ") {
            "${it.name}${if (it.variant.isNotBlank()) " (${it.variant})" else ""} ×${it.quantity}"
        }
    }

    private fun buildOrderCardView(order: OrderModel): View {
        val isDetailsOpen = expandedOrderDetailsIds.contains(order.id)
        val isEditingCustomer = editingCustomerOrderIds.contains(order.id)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(
                    dp(1),
                    if (isDetailsOpen) Color.parseColor("#F59E0B") else Color.parseColor("#1F2937")
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        // السطر العلوي: اسم الزبون + الولاية + السعر الإجمالي
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val customerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameText = TextView(this).apply {
            text = "👤 ${order.customerName}  •  📍 ${order.wilayaName}"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
        }
        customerCol.addView(nameText)

        val subInfoText = TextView(this).apply {
            val deliveryLabel = if (order.deliveryType.contains("desk") || order.deliveryType.contains("stop")) {
                "🏢 مكتب التوصيل"
            } else {
                "🏠 توصيل للمنزل"
            }
            text = "#${order.shortId} • $deliveryLabel • 📞 ${order.customerPhone}"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        customerCol.addView(subInfoText)
        topRow.addView(customerCol)

        val priceBadge = TextView(this).apply {
            text = "${formatDzd(order.totalPrice)} د.ج"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#451A03"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
        }
        topRow.addView(priceBadge)
        card.addView(topRow)

        // شريط ملخص المنتجات القابل للضغط لفتح وإغلاق نافذة تفاصيل وتأكيد الطلبية
        val summaryToggleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (isDetailsOpen) Color.parseColor("#1E293B") else Color.parseColor("#0F172A"))
                setStroke(
                    dp(1),
                    if (isDetailsOpen) Color.parseColor("#F59E0B") else Color.parseColor("#334155")
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            setOnClickListener {
                if (isDetailsOpen) {
                    expandedOrderDetailsIds.remove(order.id)
                    editingCustomerOrderIds.remove(order.id)
                    updateWindowFocusable(editingCustomerOrderIds.isNotEmpty())
                } else {
                    expandedOrderDetailsIds.add(order.id)
                }
                renderCockpitState()
            }
        }

        val itemsText = TextView(this).apply {
            text = "📦 ${order.itemsSummary}"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = if (isDetailsOpen) 4 else 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        summaryToggleBar.addView(itemsText)

        val expandHintBadge = TextView(this).apply {
            text = if (isDetailsOpen) "▴ إخفاء التفاصيل" else "📋 التفاصيل والتأكيد ▾"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#334155"))
            }
        }
        summaryToggleBar.addView(expandHintBadge)
        card.addView(summaryToggleBar)

        // درج تفاصيل وتأكيد الطلبية القابل للعرض والإخفاء (مطابق لنافذة تأكيد الطلبية)
        if (isDetailsOpen) {
            card.addView(buildCollapsibleConfirmationDrawer(order, isEditingCustomer))
        }

        // شريط الإجراءات السريعة (اتصال، واتساب، التفاصيل، وتقدم الحالة)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val callBtn = createActionChip("📞 اتصال", "#065F46", "#34D399") {
            if (order.customerPhone.isNotBlank()) {
                collapseCockpit()
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.customerPhone}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dialIntent)
            }
        }
        actionsRow.addView(callBtn)

        val waBtn = createActionChip("💬 واتساب", "#064E3B", "#6EE7B7") {
            openWhatsAppConfirmation(order)
        }
        actionsRow.addView(waBtn)

        val toggleDetailsBtn = createActionChip(
            if (isDetailsOpen) "▴ طيّ" else "📋 التفاصيل",
            "#1E293B",
            "#FDE68A"
        ) {
            if (isDetailsOpen) {
                expandedOrderDetailsIds.remove(order.id)
                editingCustomerOrderIds.remove(order.id)
                updateWindowFocusable(editingCustomerOrderIds.isNotEmpty())
            } else {
                expandedOrderDetailsIds.add(order.id)
            }
            renderCockpitState()
        }
        actionsRow.addView(toggleDetailsBtn)

        val nextAction = getNextStatusAction(order.status)
        if (nextAction != null) {
            val (nextStatusKey, nextBtnLabel, nextBgColor) = nextAction
            val progressBtn = createActionChip(nextBtnLabel, nextBgColor, "#FFFFFF") {
                updateOrderStatusInstant(order, nextStatusKey)
            }
            actionsRow.addView(progressBtn)
        }

        if (order.status != "cancelled" && order.status != "delivered") {
            val cancelBtn = createActionChip("🗑️", "#450A0A", "#FCA5A5") {
                updateOrderStatusInstant(order, "cancelled")
            }
            actionsRow.addView(cancelBtn)
        }

        card.addView(actionsRow)
        return card
    }

    private fun buildCollapsibleConfirmationDrawer(order: OrderModel, isEditingCustomer: Boolean): View {
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        // رأس نافذة تأكيد الطلبية
        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val drawerTitle = TextView(this).apply {
            text = "✅ نافذة فحص وتأكيد الطلبية #${order.shortId}"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        drawerHeader.addView(drawerTitle)

        val copyBtn = TextView(this).apply {
            text = "📋 نسخ البوليصة"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText =
                    "طلبية #${order.shortId}\nالزبون: ${order.customerName}\nالهاتف: ${order.customerPhone}\nالولاية: ${order.wilayaName} ${order.address}\nالمنتجات: ${order.itemsSummary}\nالمجموع للدفع: ${formatDzd(order.totalPrice)} د.ج"
                cm.setPrimaryClip(ClipData.newPlainText("OrderWaybill", clipText))
                Toast.makeText(this@MerchantCockpitService, "✅ تم نسخ بيانات البوليصة", Toast.LENGTH_SHORT).show()
            }
        }
        drawerHeader.addView(copyBtn)
        drawer.addView(drawerHeader)

        // 1. قائمة عناصر السلة التفصيلية مع أزرار تعديل الكمية (+ / -) وحذف عنصر
        val itemsHeader = TextView(this).apply {
            text = "🛍️ عناصر الطلبية (يمكنك تعديل الكمية أثناء مكالمة الزبون):"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
        drawer.addView(itemsHeader)

        for ((idx, item) in order.items.withIndex()) {
            val itemRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#111827"))
                    setStroke(dp(1), Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(5)
                }
            }

            val itemInfoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val itemNameTv = TextView(this).apply {
                text = "${idx + 1}. ${item.name}" + if (item.variant.isNotBlank()) " (${item.variant})" else ""
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
            }
            itemInfoCol.addView(itemNameTv)

            val itemPriceTv = TextView(this).apply {
                text = "سعر الوحدة: ${formatDzd(item.unitPrice)} د.ج  •  المجموع: ${formatDzd(item.totalPrice)} د.ج"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            itemInfoCol.addView(itemPriceTv)
            itemRow.addView(itemInfoCol)

            // أزرار التحكم بالكمية (- / العدد / +)
            val minusBtn = TextView(this).apply {
                text = "−"
                setTextColor(Color.parseColor("#FCA5A5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#311018"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener {
                    if (item.quantity > 1) {
                        item.quantity -= 1
                        recalculateOrderTotals(order)
                        renderCockpitState()
                    } else if (order.items.size > 1) {
                        order.items.removeAt(idx)
                        recalculateOrderTotals(order)
                        renderCockpitState()
                    }
                }
            }
            itemRow.addView(minusBtn)

            val qtyText = TextView(this).apply {
                text = "${item.quantity}"
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
            }
            itemRow.addView(qtyText)

            val plusBtn = TextView(this).apply {
                text = "+"
                setTextColor(Color.parseColor("#34D399"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#064E3B"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener {
                    item.quantity += 1
                    recalculateOrderTotals(order)
                    renderCockpitState()
                }
            }
            itemRow.addView(plusBtn)

            if (order.items.size > 1) {
                val removeItemBtn = TextView(this).apply {
                    text = "🗑️"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        order.items.removeAt(idx)
                        recalculateOrderTotals(order)
                        renderCockpitState()
                    }
                }
                itemRow.addView(removeItemBtn)
            }

            drawer.addView(itemRow)
        }

        // 2. قسم بيانات التوصيل والعنوان والملاحظات (مع إمكانية التعديل المباشر داخل الكبسولة)
        val addressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        }

        if (!isEditingCustomer) {
            val addrHeaderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val addrDetailsTv = TextView(this).apply {
                val fullAddr = buildString {
                    append("📍 العنوان: ${order.wilayaName}")
                    if (order.communeName.isNotBlank() && order.communeName != order.address) {
                        append(" - ${order.communeName}")
                    }
                    if (order.address.isNotBlank()) {
                        append(" (${order.address})")
                    }
                    if (order.notes.isNotBlank()) {
                        append("\n📝 ملاحظة الزبون: ${order.notes}")
                    }
                }
                text = fullAddr
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addrHeaderRow.addView(addrDetailsTv)

            val editCustomerBtn = TextView(this).apply {
                text = "✏️ تعديل العنوان/الاسم"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#0C4A6E"))
                }
                setOnClickListener {
                    editingCustomerOrderIds.add(order.id)
                    updateWindowFocusable(true)
                    renderCockpitState()
                }
            }
            addrHeaderRow.addView(editCustomerBtn)
            addressBox.addView(addrHeaderRow)
        } else {
            // حقول تحرير بيانات الزبون مباشرةً داخل الكبسولة
            val editTitle = TextView(this).apply {
                text = "✏️ تعديل بيانات الزبون والعنوان:"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(null, Typeface.BOLD)
            }
            addressBox.addView(editTitle)

            val nameInput = createInlineEditField(order.customerName, "اسم الزبون")
            val phoneInput = createInlineEditField(order.customerPhone, "رقم الهاتف", InputType.TYPE_CLASS_PHONE)
            val wilayaInput = createInlineEditField(order.wilayaName, "الولاية")
            val addressInput = createInlineEditField(order.address, "البلدية / العنوان التفصيلي")
            val notesInput = createInlineEditField(order.notes, "ملاحظات التوصيل أو المقاس")

            addressBox.addView(nameInput)
            addressBox.addView(phoneInput)
            addressBox.addView(wilayaInput)
            addressBox.addView(addressInput)
            addressBox.addView(notesInput)

            val editActionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                }
            }

            val doneEditBtn = TextView(this).apply {
                text = "✔️ اعتماد البيانات"
                setTextColor(Color.parseColor("#090D16"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#38BDF8"))
                }
                setOnClickListener {
                    order.customerName = nameInput.text.toString().trim().ifEmpty { order.customerName }
                    order.customerPhone = phoneInput.text.toString().trim().ifEmpty { order.customerPhone }
                    order.wilayaName = wilayaInput.text.toString().trim().ifEmpty { order.wilayaName }
                    order.address = addressInput.text.toString().trim()
                    order.notes = notesInput.text.toString().trim()
                    editingCustomerOrderIds.remove(order.id)
                    hideKeyboard(this)
                    updateWindowFocusable(editingCustomerOrderIds.isNotEmpty())
                    renderCockpitState()
                }
            }
            editActionsRow.addView(doneEditBtn)
            addressBox.addView(editActionsRow)
        }
        drawer.addView(addressBox)

        // 3. الملخص المالي التفصيلي (المجموع الفرعي + التحكم بسعر التوصيل + الإجمالي النهائي)
        val financeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subRow.addView(TextView(this).apply {
            text = "مجموع المنتجات (${order.itemsCount} قطعة):"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        subRow.addView(TextView(this).apply {
            text = "${formatDzd(order.subtotalPrice)} د.ج"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
        })
        financeBox.addView(subRow)

        val shipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
        }
        shipRow.addView(TextView(this).apply {
            text = "🚚 سعر التوصيل:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // أزرار تعديل سريع لسعر التوصيل (-50 / مجاني / +50)
        val shipMinusBtn = createMiniShippingBtn("-50") {
            order.shippingCost = max(0.0, order.shippingCost - 50.0)
            recalculateOrderTotals(order)
            renderCockpitState()
        }
        shipRow.addView(shipMinusBtn)

        val shipFreeBtn = createMiniShippingBtn("مجاني") {
            order.shippingCost = 0.0
            recalculateOrderTotals(order)
            renderCockpitState()
        }
        shipRow.addView(shipFreeBtn)

        val shipPlusBtn = createMiniShippingBtn("+50") {
            order.shippingCost += 50.0
            recalculateOrderTotals(order)
            renderCockpitState()
        }
        shipRow.addView(shipPlusBtn)

        shipRow.addView(TextView(this).apply {
            text = "${formatDzd(order.shippingCost)} د.ج"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        })
        financeBox.addView(shipRow)

        val grandTotalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        grandTotalRow.addView(TextView(this).apply {
            text = "💰 الإجمالي النهائي للدفع (COD):"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        grandTotalRow.addView(TextView(this).apply {
            text = "${formatDzd(order.totalPrice)} د.ج"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
        })
        financeBox.addView(grandTotalRow)
        drawer.addView(financeBox)

        // 4. أزرار الحفظ الفوري وتأكيد الطلبية من داخل الدرج
        val drawerBottomBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val saveEditsBtn = TextView(this).apply {
            text = "💾 حفظ التعديلات فوراً"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1D4ED8"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                saveOrderDetailsToServer(order, confirmStatusToo = false)
            }
        }
        drawerBottomBtns.addView(saveEditsBtn)

        if (order.status == "pending" || order.status == "new") {
            val confirmAndSaveBtn = TextView(this).apply {
                text = "✔️ تأكيد الطلبية وحفظها"
                setTextColor(Color.parseColor("#090D16"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#10B981"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    saveOrderDetailsToServer(order, confirmStatusToo = true)
                }
            }
            drawerBottomBtns.addView(confirmAndSaveBtn)
        } else if (order.status == "confirmed" || order.status == "processing" || order.status == "preparing") {
            val shipAndSaveBtn = TextView(this).apply {
                text = "🚚 تسليم لشركة التوصيل الآن"
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#6D28D9"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    order.status = "out_for_delivery"
                    saveOrderDetailsToServer(order, confirmStatusToo = false, explicitStatus = "out_for_delivery")
                }
            }
            drawerBottomBtns.addView(shipAndSaveBtn)
        }

        drawer.addView(drawerBottomBtns)
        return drawer
    }

    private fun createInlineEditField(
        initialValue: String,
        hintText: String,
        inputTypeFlag: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        return EditText(this).apply {
            setText(initialValue)
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            inputType = inputTypeFlag
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0B1120"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
            }
        }
    }

    private fun createMiniShippingBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun hideKeyboard(view: View) {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun createActionChip(
        label: String,
        bgColorHex: String,
        textColorHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(textColorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bgColorHex))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun getNextStatusAction(currentStatus: String): Triple<String, String, String>? {
        return when (currentStatus.lowercase()) {
            "pending", "new" -> Triple("confirmed", "✔️ تأكيد", "#1D4ED8")
            "confirmed", "processing", "preparing" -> Triple("out_for_delivery", "🚚 شحن", "#6D28D9")
            "shipped", "in_transit", "out_for_delivery" -> Triple("delivered", "🎉 تسليم", "#047857")
            else -> null
        }
    }

    private fun saveOrderDetailsToServer(
        order: OrderModel,
        confirmStatusToo: Boolean,
        explicitStatus: String? = null
    ) {
        val prevStatus = order.status
        if (confirmStatusToo) {
            order.status = "confirmed"
        } else if (!explicitStatus.isNullOrBlank()) {
            order.status = explicitStatus
        }
        renderCockpitState()

        networkExecutor.execute {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val baseUrl = (prefs.getString(
                    "base_url",
                    "https://my-private-store-app.vercel.app"
                ) ?: "https://my-private-store-app.vercel.app").trimEnd('/')

                val conn = (URL("$baseUrl/api/merchant/companion").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 9000
                    readTimeout = 9000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val itemsJson = JSONArray()
                for (it in order.items) {
                    itemsJson.put(
                        JSONObject().apply {
                            put("id", it.id)
                            if (it.productId != null) put("product_id", it.productId)
                            if (it.variantId != null) put("variant_id", it.variantId)
                            put("name", it.name)
                            put("variant", it.variant)
                            put("quantity", it.quantity)
                            put("unit_price", it.unitPrice)
                        }
                    )
                }

                val payload = JSONObject().apply {
                    put("action", "update_order_details")
                    put("order_id", order.id)
                    put("customer_name", order.customerName)
                    put("customer_phone", order.customerPhone)
                    put("wilaya_name", order.wilayaName)
                    put("address", order.address)
                    put("notes", order.notes)
                    put("shipping_cost", order.shippingCost)
                    put("total_price", order.totalPrice)
                    if (confirmStatusToo) {
                        put("status", "confirmed")
                    } else if (!explicitStatus.isNullOrBlank()) {
                        put("status", explicitStatus)
                    }
                    put("items", itemsJson)
                }

                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }

                val code = conn.responseCode
                conn.disconnect()

                mainHandler.post {
                    if (code in 200..299) {
                        Toast.makeText(
                            this@MerchantCockpitService,
                            "✅ تم حفظ تفاصيل الطلبية #${order.shortId} بنجاح",
                            Toast.LENGTH_SHORT
                        ).show()
                        fetchMerchantOrders(manualTrigger = false)
                    } else {
                        order.status = prevStatus
                        renderCockpitState()
                        Toast.makeText(
                            this@MerchantCockpitService,
                            "⚠️ تعذر حفظ التعديلات على الخادم",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    order.status = prevStatus
                    renderCockpitState()
                    Toast.makeText(
                        this@MerchantCockpitService,
                        "⚠️ خطأ في الاتصال أثناء الحفظ",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateOrderStatusInstant(order: OrderModel, newStatus: String) {
        val oldStatus = order.status
        order.status = newStatus
        renderCockpitState()

        networkExecutor.execute {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val baseUrl = (prefs.getString(
                    "base_url",
                    "https://my-private-store-app.vercel.app"
                ) ?: "https://my-private-store-app.vercel.app").trimEnd('/')

                val conn = (URL("$baseUrl/api/merchant/companion").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("action", "update_status")
                    put("order_id", order.id)
                    put("status", newStatus)
                }

                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }

                val code = conn.responseCode
                conn.disconnect()

                if (code !in 200..299) {
                    mainHandler.post {
                        order.status = oldStatus
                        renderCockpitState()
                        Toast.makeText(
                            this@MerchantCockpitService,
                            "⚠️ تعذر تحديث الحالة على الخادم",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(
                            this@MerchantCockpitService,
                            "✅ تم تحديث الطلبية #${order.shortId}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    order.status = oldStatus
                    renderCockpitState()
                }
            }
        }
    }

    private fun openWhatsAppConfirmation(order: OrderModel) {
        val rawDigits = order.customerPhone.replace(Regex("[^0-9]"), "")
        if (rawDigits.isEmpty()) return

        val intlPhone = when {
            rawDigits.startsWith("213") -> rawDigits
            rawDigits.startsWith("0") -> "213" + rawDigits.substring(1)
            else -> "213$rawDigits"
        }

        val message =
            "مرحباً ${order.customerName} 👋، معك متجر $storeName بخصوص طلبيتك رقم #${order.shortId} (${order.itemsSummary}) إلى ولاية ${order.wilayaName} بمبلغ إجمالي ${formatDzd(order.totalPrice)} د.ج. هل نؤكد إرسال الطلبية لك اليوم؟"

        val waUrl = "https://wa.me/$intlPhone?text=${URLEncoder.encode(message, "UTF-8")}"
        collapseCockpit()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun formatDzd(amount: Double): String {
        return try {
            NumberFormat.getInstance(Locale("fr", "DZ")).format(amount.toLong())
        } catch (_: Exception) {
            amount.toLong().toString()
        }
    }

    private fun playOrderArrivalChime(previewOnly: Boolean) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString("ringtone_mode", "cashier_gold") ?: "cashier_gold"

            // اهتزاز تكتيكي عند وصول الطلبية
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 140, 80, 200), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(320)
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
                        if (previewOnly) {
                            mainHandler.postDelayed({
                                try {
                                    ringtone?.stop()
                                } catch (_: Exception) {}
                            }, 3500L)
                        }
                        return
                    } catch (_: Exception) {}
                }
            }

            // توليد النغمة المختارة برمجياً بدقة عالية
            networkExecutor.execute {
                try {
                    val notesSequence: List<Pair<Double, Int>> = when (mode) {
                        "radar_urgent" -> listOf(
                            880.0 to 110,
                            1174.66 to 110,
                            880.0 to 110,
                            1318.51 to 220
                        )
                        "royal_chime" -> listOf(
                            523.25 to 160,
                            659.25 to 160,
                            783.99 to 180,
                            1046.50 to 300
                        )
                        "classic_bell" -> listOf(
                            659.25 to 180,
                            523.25 to 260
                        )
                        else -> listOf( // cashier_gold
                            587.33 to 120,
                            880.0 to 140,
                            1174.66 to 260
                        )
                    }

                    val sampleRate = 22050
                    val totalDurationMs = notesSequence.sumOf { it.second + 25 }
                    val totalSamples = (sampleRate * (totalDurationMs / 1000.0)).toInt()
                    val pcmBuffer = ShortArray(totalSamples)

                    var cursor = 0
                    for ((freq, durMs) in notesSequence) {
                        val nSamples = (sampleRate * (durMs / 1000.0)).toInt()
                        for (i in 0 until nSamples) {
                            if (cursor >= totalSamples) break
                            val env = 1.0 - (i.toDouble() / nSamples.toDouble()) * 0.65
                            val wave = sin(2.0 * Math.PI * i * freq / sampleRate)
                            pcmBuffer[cursor++] = (wave * Short.MAX_VALUE * 0.45 * env).toInt().toShort()
                        }
                        val gapSamples = (sampleRate * 0.025).toInt()
                        cursor = min(totalSamples, cursor + gapSamples)
                    }

                    val track = AudioTrack(
                        AudioManager.STREAM_NOTIFICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        pcmBuffer.size * 2,
                        AudioTrack.MODE_STATIC
                    )
                    track.write(pcmBuffer, 0, pcmBuffer.size)
                    track.play()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}

// كلاس رديف لضمان التوافق التام إذا كان AndroidManifest يشير إلى FloatingBubbleService
class FloatingBubbleService : MerchantCockpitService()
