package com.dzmerchant.cockpit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class MerchantCockpitOverlayView(
    context: Context,
    private val onPlayChime: () -> Unit,
    private val onPickPhoneRingtone: () -> Unit,
    private val onSetFocusable: (Boolean) -> Unit,
    private val onHideStealth: () -> Unit,
    private val onCloseService: () -> Unit,
    private val onOrderAction: (String, String) -> Unit,
    private val onSaveOrderDetails: (MerchantOrderItem) -> Unit,
    private val onToggleStore: (String, Boolean) -> Unit,
    private val onSelectStore: (String) -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)

    val bubbleWrapper: FrameLayout
    private val badgeText: TextView
    private val cockpitCard: LinearLayout
    private val storeTitleText: TextView
    private val storeLockBtn: TextView
    private val storeTabsRow: LinearLayout

    // عناصر فلتر الوقت والمقارنة البصرية
    private val timeRangeTabs = mutableMapOf<String, TextView>()
    private val dateNavLabelText: TextView
    private val dateResetTodayBtn: TextView
    private val dateNextArrowBtn: TextView
    private val gmvTitleLabel: TextView
    private val gmvValueText: TextView
    private val gmvOrdersBadge: TextView
    private val gmvDeltaCompareText: TextView
    private val gmvCurrentBar: View
    private val gmvPrevBar: View
    private val codValueText: TextView

    private val tabNewBox: LinearLayout
    private val tabPrepBox: LinearLayout
    private val tabShipBox: LinearLayout
    private val tabDelivBox: LinearLayout

    private val tabNewCount: TextView
    private val tabPrepCount: TextView
    private val tabShipCount: TextView
    private val tabDelivCount: TextView

    private val pagerText: TextView
    private val orderRefText: TextView
    private val orderTimeText: TextView
    private val customerNameText: TextView
    private val orderPriceText: TextView
    private val customerPhoneText: TextView
    private val carrierBadgeText: TextView
    private val wilayaBadgeText: TextView

    // شريط المنتجات القابل للضغط + درج تأكيد وتعديل الطلبية المدمج
    private val itemsSummaryBar: LinearLayout
    private val itemsSummaryText: TextView
    private val itemsExpandHintBadge: TextView
    private val inlineConfirmationDrawer: LinearLayout
    private val primaryActionBtn: TextView
    private val btnDetailsToggle: TextView

    // لوحة تخصيص نغمة الرنين
    private val ringtonePanel: LinearLayout
    private val btnRingtoneToggle: TextView

    private var currentPayload: CockpitPayload? = null
    private var selectedStoreFilter: String = "all"
    private var selectedStage: String = "new"
    private var currentOrderIndex: Int = 0
    private val localStageOverrides = mutableMapOf<String, String>()

    // حالة الفلتر الزمني ودرج التفاصيل والنغمة
    private var selectedTimeRange: String = "all" // "day", "week", "month", "all"
    private var selectedDateOffset: Int = 0       // 0 = اليوم, -1 = البارحة, -2 = قبل البارحة...
    private var isDetailsDrawerOpen: Boolean = false
    private var isEditingCustomerInfo: Boolean = false
    private var isRingtonePanelOpen: Boolean = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.END

        bubbleWrapper = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(62), dp(62))
        }

        val circleBubble = TextView(context).apply {
            text = "👑"
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0D0D12"))
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
        }

        val onlineDot = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
                setStroke(dp(2), Color.parseColor("#0D0D12"))
            }
        }

        badgeText = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(1), dp(5), dp(1))
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E11D48"))
            }
            visibility = View.GONE
        }

        bubbleWrapper.addView(circleBubble)
        bubbleWrapper.addView(onlineDot)
        bubbleWrapper.addView(badgeText)
        addView(bubbleWrapper)

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.94).toInt().coerceAtMost(dp(380))

        cockpitCard = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#0D0D12"))
                setStroke(dp(1), Color.parseColor("#3F2E14"))
            }
            visibility = View.GONE
        }

        // شريط الرأس العلوي الثابت
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(30), dp(30))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E11D48"))
            }
            setOnClickListener {
                closeInlineEditors()
                onHideStealth()
            }
        }

        val minBtn = TextView(context).apply {
            text = "─"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(30), dp(30)).apply {
                leftMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#27272A"))
            }
            setOnClickListener {
                closeInlineEditors()
                toggleCockpit(false)
            }
        }

        storeLockBtn = TextView(context).apply {
            text = "🟢 مفتوح 🔓"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(5), dp(9), dp(5))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#064E3B"))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            setOnClickListener {
                currentPayload?.let { p ->
                    val nextOpen = !p.isStoreOpen
                    p.isStoreOpen = nextOpen
                    updatePayload(p)
                    onToggleStore(p.storeId, nextOpen)
                }
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        storeTitleText = TextView(context).apply {
            text = "✨ Elegance"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }

        val subHeader = TextView(context).apply {
            text = "كبسولة القيادة الفورية للتاجر"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 9.5f
            gravity = Gravity.END
        }

        titleCol.addView(storeTitleText)
        titleCol.addView(subHeader)

        headerRow.addView(closeBtn)
        headerRow.addView(minBtn)
        headerRow.addView(storeLockBtn)
        headerRow.addView(titleCol)
        cockpitCard.addView(headerRow)

        // حاوية تمرير ذكية بحد أقصى 78% من ارتفاع الشاشة حتى لا تخرج الكبسولة عن الشاشة عند فتح الدرج
        val bodyScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.78f).toInt()
                val cappedSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cappedSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val bodyContent = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val storeScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        }
        storeTabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }
        storeScroll.addView(storeTabsRow)
        bodyContent.addView(storeScroll)

        // ==========================================================
        // الميزة 2: شريط تحديد النطاق الزمني (يومي/أسبوعي/شهري/الكل) + أسهم الرجوع للأيام السابقة
        // ==========================================================
        val timeControlBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }

        val periodTabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }

        val periods = listOf(
            "all" to "♾️ الكل",
            "month" to "🗓️ شهري",
            "week" to "📆 أسبوعي",
            "day" to "📅 يومي"
        )

        for ((key, label) in periods) {
            val tab = TextView(context).apply {
                text = label
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(5), dp(6), dp(5))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    selectedTimeRange = key
                    selectedDateOffset = 0
                    currentOrderIndex = 0
                    refreshAllAnalyticsAndCards()
                }
            }
            timeRangeTabs[key] = tab
            periodTabsRow.addView(tab)
        }
        timeControlBox.addView(periodTabsRow)

        val dateArrowsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        dateNextArrowBtn = TextView(context).apply {
            text = "◀ التالي"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1F1F23"))
            }
            setOnClickListener {
                if (selectedDateOffset < 0) {
                    selectedDateOffset += 1
                    currentOrderIndex = 0
                    refreshAllAnalyticsAndCards()
                }
            }
        }

        dateResetTodayBtn = TextView(context).apply {
            text = "↺ اليوم"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0C4A6E"))
            }
            setOnClickListener {
                selectedDateOffset = 0
                currentOrderIndex = 0
                refreshAllAnalyticsAndCards()
            }
        }

        dateNavLabelText = TextView(context).apply {
            text = "📅 اليوم (مقارنة بالبارحة)"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val datePrevArrowBtn = TextView(context).apply {
            text = "السابق ▶"
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#2A1B05"))
                setStroke(dp(1), Color.parseColor("#92400E"))
            }
            setOnClickListener {
                if (selectedTimeRange == "all") {
                    selectedTimeRange = "day"
                }
                selectedDateOffset -= 1
                currentOrderIndex = 0
                refreshAllAnalyticsAndCards()
            }
        }

        dateArrowsRow.addView(dateNextArrowBtn)
        dateArrowsRow.addView(dateResetTodayBtn)
        dateArrowsRow.addView(dateNavLabelText)
        dateArrowsRow.addView(datePrevArrowBtn)
        timeControlBox.addView(dateArrowsRow)
        bodyContent.addView(timeControlBox)

        // بطاقتا السيولة بالشارع (COD) ومبيعات اليوم/الفترة (GMV) مع شريط المقارنة البصرية
        val financeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        val codCard = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.9f).apply {
                rightMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }
        val codLabel = TextView(context).apply {
            text = "السيولة بالشارع (COD)"
            setTextColor(Color.parseColor("#D97706"))
            textSize = 9.5f
        }
        codValueText = TextView(context).apply {
            text = "0 د.ج 💰"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        codCard.addView(codLabel)
        codCard.addView(codValueText)

        val gmvCard = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.15f).apply {
                leftMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }
        gmvTitleLabel = TextView(context).apply {
            text = "مبيعات اليوم (GMV)"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 9.5f
        }
        val gmvInnerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, dp(3), 0, 0)
        }
        gmvOrdersBadge = TextView(context).apply {
            text = "0 طلب"
            setTextColor(Color.parseColor("#818CF8"))
            textSize = 9.5f
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1E1B4B"))
            }
        }
        gmvValueText = TextView(context).apply {
            text = "0 د.ج"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(5), 0, 0, 0)
        }
        gmvInnerRow.addView(gmvOrdersBadge)
        gmvInnerRow.addView(gmvValueText)

        gmvDeltaCompareText = TextView(context).apply {
            text = "مقابل البارحة: 0 د.ج"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setPadding(0, dp(3), 0, dp(3))
        }

        val barsWrapper = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        gmvCurrentBar = View(context).apply {
            layoutParams = LayoutParams(dp(100), dp(4)).apply {
                bottomMargin = dp(2)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#10B981"))
            }
        }
        gmvPrevBar = View(context).apply {
            layoutParams = LayoutParams(dp(60), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#52525B"))
            }
        }
        barsWrapper.addView(gmvCurrentBar)
        barsWrapper.addView(gmvPrevBar)

        gmvCard.addView(gmvTitleLabel)
        gmvCard.addView(gmvInnerRow)
        gmvCard.addView(gmvDeltaCompareText)
        gmvCard.addView(barsWrapper)

        financeRow.addView(codCard)
        financeRow.addView(gmvCard)
        bodyContent.addView(financeRow)

        // المراحل الأربع (جديد | تجهيز | في الطريق | تم التسليم)
        val stagesRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        fun createStageTab(label: String, icon: String, stageKey: String): Pair<LinearLayout, TextView> {
            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(7), dp(5), dp(7))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    selectedStage = stageKey
                    currentOrderIndex = 0
                    closeInlineEditors()
                    refreshStageHighlight()
                    refreshOrderCard()
                }
            }
            val countTv = TextView(context).apply {
                text = "0 $icon"
                setTextColor(Color.WHITE)
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val titleTv = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 9.5f
                gravity = Gravity.CENTER
            }
            box.addView(countTv)
            box.addView(titleTv)
            return Pair(box, countTv)
        }

        val delivPair = createStageTab("تم التسليم", "✅", "delivered")
        tabDelivBox = delivPair.first
        tabDelivCount = delivPair.second

        val shipPair = createStageTab("في الطريق", "🚚", "shipping")
        tabShipBox = shipPair.first
        tabShipCount = shipPair.second

        val prepPair = createStageTab("تجهيز", "📦", "preparing")
        tabPrepBox = prepPair.first
        tabPrepCount = prepPair.second

        val newPair = createStageTab("جديد", "⏳", "new")
        tabNewBox = newPair.first
        tabNewCount = newPair.second

        stagesRow.addView(tabDelivBox)
        stagesRow.addView(tabShipBox)
        stagesRow.addView(tabPrepBox)
        stagesRow.addView(tabNewBox)
        bodyContent.addView(stagesRow)

        // بطاقة الطلبية الحالية
        val orderDeck = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }

        val deckTopRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val prevBtn = TextView(context).apply {
            text = "<"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(28), dp(28))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#27272A"))
            }
            setOnClickListener {
                if (currentOrderIndex > 0) {
                    currentOrderIndex--
                    closeInlineEditors()
                    refreshOrderCard()
                }
            }
        }

        pagerText = TextView(context).apply {
            text = "0 / 0"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(8), 0, dp(8), 0)
        }

        val nextBtn = TextView(context).apply {
            text = ">"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(28), dp(28))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#27272A"))
            }
            setOnClickListener {
                val filtered = getFilteredOrders()
                if (currentOrderIndex < filtered.size - 1) {
                    currentOrderIndex++
                    closeInlineEditors()
                    refreshOrderCard()
                }
            }
        }

        orderTimeText = TextView(context).apply {
            text = "🕒 الآن"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 10f
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }

        orderRefText = TextView(context).apply {
            text = "#---"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#2A1B05"))
                setStroke(dp(1), Color.parseColor("#92400E"))
            }
        }

        deckTopRow.addView(prevBtn)
        deckTopRow.addView(pagerText)
        deckTopRow.addView(nextBtn)
        deckTopRow.addView(orderTimeText)
        deckTopRow.addView(orderRefText)
        orderDeck.addView(deckTopRow)

        val custRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

        orderPriceText = TextView(context).apply {
            text = "0 د.ج"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        customerNameText = TextView(context).apply {
            text = "👤 لا توجد طلبات"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        custRow.addView(orderPriceText)
        custRow.addView(customerNameText)
        orderDeck.addView(custRow)

        val subCustRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
                bottomMargin = dp(7)
            }
        }

        wilayaBadgeText = TextView(context).apply {
            text = "📍 43 - ميلة"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 10f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1E1B4B"))
                setStroke(dp(1), Color.parseColor("#3730A3"))
            }
        }

        val spacer = View(context).apply {
            layoutParams = LayoutParams(0, 1, 1f)
        }

        carrierBadgeText = TextView(context).apply {
            text = "موبيليس"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#064E3B"))
            }
        }

        customerPhoneText = TextView(context).apply {
            text = "---"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }

        subCustRow.addView(wilayaBadgeText)
        subCustRow.addView(spacer)
        subCustRow.addView(carrierBadgeText)
        subCustRow.addView(customerPhoneText)
        orderDeck.addView(subCustRow)

        // ==========================================================
        // الميزة 1: شريط السلع القابل للفتح والإغلاق + درج تأكيد وتعديل الطلبية المدمج
        // ==========================================================
        itemsSummaryBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#09090B"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            setOnClickListener {
                toggleOrderDetailsDrawer()
            }
        }

        itemsExpandHintBadge = TextView(context).apply {
            text = "📋 التفاصيل ▾"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(Color.parseColor("#2A1B05"))
                setStroke(dp(1), Color.parseColor("#92400E"))
            }
        }

        itemsSummaryText = TextView(context).apply {
            text = "---"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 11f
            gravity = Gravity.END
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            }
        }

        itemsSummaryBar.addView(itemsExpandHintBadge)
        itemsSummaryBar.addView(itemsSummaryText)
        orderDeck.addView(itemsSummaryBar)

        inlineConfirmationDrawer = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
        }
        orderDeck.addView(inlineConfirmationDrawer)

        primaryActionBtn = TextView(context).apply {
            text = "⚡ قبول وتأكيد الطلب فوراً ⚡"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#3B82F6"))
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                val ord = getCurrentOrder() ?: return@setOnClickListener
                val nextStageAndStatus = when (ord.stage) {
                    "new" -> Pair("preparing", "preparing")
                    "preparing" -> Pair("shipping", "shipped")
                    "shipping" -> Pair("delivered", "delivered")
                    else -> null
                } ?: return@setOnClickListener

                val nextStage = nextStageAndStatus.first
                val nextStatus = nextStageAndStatus.second

                localStageOverrides[ord.id] = nextStage
                ord.stage = nextStage
                ord.status = nextStatus

                closeInlineEditors()
                recalculateCountsAndFollowOrder(ord.id, nextStage)
                onOrderAction(ord.id, nextStatus)
            }
        }
        orderDeck.addView(primaryActionBtn)

        val quickActionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        fun createQuickActionBtn(title: String, isGreen: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = title
                setTextColor(if (isGreen) Color.parseColor("#34D399") else Color.parseColor("#E4E4E7"))
                textSize = 10.5f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (isGreen) Color.parseColor("#064E3B") else Color.parseColor("#1F1F23"))
                    setStroke(dp(1), if (isGreen) Color.parseColor("#059669") else Color.parseColor("#3F3F46"))
                }
                setOnClickListener { onClick() }
            }
        }

        btnDetailsToggle = createQuickActionBtn("التفاصيل ▾") {
            toggleOrderDetailsDrawer()
        }

        val btnWaybill = createQuickActionBtn("بوليصة 📋") {
            val ord = getCurrentOrder() ?: return@createQuickActionBtn
            val waybill = "بوليصة شحن ${ord.ref}\nالزبون: ${ord.customerName}\nالهاتف: ${ord.customerPhone}\nالولاية: ${ord.wilaya} ${ord.commune}\nالسلع: ${ord.itemsSummary}\nالمبلغ المستحق (COD): ${ord.total} د.ج"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Waybill", waybill))
            Toast.makeText(context, "تم نسخ بوليصة الشحن 📋", Toast.LENGTH_SHORT).show()
        }

        val btnWhatsapp = createQuickActionBtn("واتساب 💬", isGreen = true) {
            val ord = getCurrentOrder() ?: return@createQuickActionBtn
            val cleanPhone = ord.customerPhone.replace("\\s+".toRegex(), "").replace("^0".toRegex(), "213").replace("^\\+".toRegex(), "")
            val msg = Uri.encode("مرحباً ${ord.customerName}، معك متجر ${currentPayload?.storeName ?: "Elegance"} بخصوص طلبيتك (${ord.itemsSummary}) بمبلغ ${ord.total} د.ج إلى ${ord.wilaya}. هل نؤكد إرسال الطرد اليوم؟")
            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanPhone?text=$msg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(waIntent)
        }

        val btnCall = createQuickActionBtn("اتصال 📞") {
            val ord = getCurrentOrder() ?: return@createQuickActionBtn
            val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ord.customerPhone}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
        }

        quickActionsRow.addView(btnDetailsToggle)
        quickActionsRow.addView(btnWaybill)
        quickActionsRow.addView(btnWhatsapp)
        quickActionsRow.addView(btnCall)
        orderDeck.addView(quickActionsRow)

        bodyContent.addView(orderDeck)

        // ==========================================================
        // الميزة 3: لوحة تخصيص نغمة الرنين القابلة للعرض والإخفاء
        // ==========================================================
        ringtonePanel = buildRingtoneSelectorPanel()
        bodyContent.addView(ringtonePanel)

        val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val btnHideStealth = TextView(context).apply {
            text = "👁️ إخفاء 🙈"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(3)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            setOnClickListener {
                closeInlineEditors()
                onHideStealth()
            }
        }

        btnRingtoneToggle = TextView(context).apply {
            text = "🎵 النغمة ▾"
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#2A1B05"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setOnClickListener {
                isRingtonePanelOpen = !isRingtonePanelOpen
                ringtonePanel.visibility = if (isRingtonePanelOpen) View.VISIBLE else View.GONE
                refreshRingtoneLabel()
            }
        }

        val btnTestSound = TextView(context).apply {
            text = "🔔 تجربة الرنين"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(3)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
            setOnClickListener { onPlayChime() }
        }

        footerRow.addView(btnHideStealth)
        footerRow.addView(btnRingtoneToggle)
        footerRow.addView(btnTestSound)
        bodyContent.addView(footerRow)

        bodyScroll.addView(bodyContent)
        cockpitCard.addView(bodyScroll)

        addView(cockpitCard)
        refreshRingtoneLabel()
        refreshStageHighlight()
    }

    private fun closeInlineEditors() {
        isDetailsDrawerOpen = false
        isEditingCustomerInfo = false
        inlineConfirmationDrawer.visibility = View.GONE
        hideSoftKeyboard()
        onSetFocusable(false)
    }

    private fun toggleOrderDetailsDrawer() {
        val ord = getCurrentOrder() ?: return
        isDetailsDrawerOpen = !isDetailsDrawerOpen
        if (!isDetailsDrawerOpen) {
            isEditingCustomerInfo = false
            hideSoftKeyboard()
            onSetFocusable(false)
        }
        refreshOrderCard()
    }

    private fun buildRingtoneSelectorPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
        }

        val title = TextView(context).apply {
            text = "🎵 اختر نغمة رنين الطلبات الجديدة للكبسولة:"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        panel.addView(title)

        val options = listOf(
            "cashier_gold" to "💰 جرس الكاشير الذهبي",
            "radar_urgent" to "🚨 رادار الطوارئ السريع",
            "royal_chime" to "👑 النغمة الملكية الفاخرة",
            "classic_bell" to "🔔 رنين المتجر الكلاسيكي",
            "custom_uri" to "📱 اختيار نغمة مخصصة من الهاتف...",
            "silent_vibrate" to "📳 اهتزاز صامت فقط"
        )

        for ((key, label) in options) {
            val row = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.END
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(Color.parseColor("#1F1F23"))
                    setStroke(dp(1), Color.parseColor("#3F3F46"))
                }
                setOnClickListener {
                    if (key == "custom_uri") {
                        onPickPhoneRingtone()
                    } else {
                        prefs.edit().putString("ringtone_mode", key).apply()
                        refreshRingtoneLabel()
                        onPlayChime()
                        Toast.makeText(context, "✅ تم اعتماد: $label", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            panel.addView(row)
        }
        return panel
    }

    fun refreshRingtoneLabel() {
        val mode = prefs.getString("ringtone_mode", "cashier_gold") ?: "cashier_gold"
        val customName = prefs.getString("custom_ringtone_title", null)
        val shortTitle = when (mode) {
            "cashier_gold" -> "🎵 الكاشير"
            "radar_urgent" -> "🚨 رادار"
            "royal_chime" -> "👑 ملكي"
            "classic_bell" -> "🔔 كلاسيك"
            "custom_uri" -> if (!customName.isNullOrBlank()) "📱 ${customName.take(10)}" else "📱 الهاتف"
            "silent_vibrate" -> "📳 صامت"
            else -> "🎵 النغمة"
        }
        btnRingtoneToggle.text = if (isRingtonePanelOpen) "$shortTitle ▴" else "$shortTitle ▾"
    }

    private fun recalculateOrderTotals(ord: MerchantOrderItem) {
        val sub = ord.items.sumOf { it.unitPrice * it.quantity }
        ord.subtotal = sub
        ord.total = max(0, sub + ord.shippingCost)
        ord.itemsSummary = ord.items.joinToString(" ، ") {
            "${it.name}${if (it.variant.isNotBlank()) " (${it.variant})" else ""} ×${it.quantity}"
        }
    }

    private fun renderInlineConfirmationDrawer(ord: MerchantOrderItem) {
        inlineConfirmationDrawer.removeAllViews()
        if (!isDetailsDrawerOpen) {
            inlineConfirmationDrawer.visibility = View.GONE
            return
        }
        inlineConfirmationDrawer.visibility = View.VISIBLE
        val nf = NumberFormat.getIntegerInstance(Locale.US)

        // شريط عنوان الدرج + زر فتح في المتصفح
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            }
        }

        val openWebBtn = TextView(context).apply {
            text = "↗️ المتصفح"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 9.5f
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#1F1F23"))
            }
            setOnClickListener {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my-private-store-app.vercel.app/merchant/orders")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }
        }

        val drawerTitle = TextView(context).apply {
            text = "✅ تفاصيل وتأكيد الطلبية ${ord.ref}"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        headerRow.addView(openWebBtn)
        headerRow.addView(drawerTitle)
        inlineConfirmationDrawer.addView(headerRow)

        // 1. قائمة المنتجات مع أزرار (+ / - / حذف)
        for ((idx, item) in ord.items.withIndex()) {
            val itemBox = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(6), dp(7), dp(6))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(5)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(Color.parseColor("#141418"))
                    setStroke(dp(1), Color.parseColor("#27272A"))
                }
            }

            if (ord.items.size > 1) {
                val delBtn = TextView(context).apply {
                    text = "🗑️"
                    textSize = 11f
                    setPadding(dp(4), dp(2), dp(6), dp(2))
                    setOnClickListener {
                        ord.items.removeAt(idx)
                        recalculateOrderTotals(ord)
                        refreshOrderCard()
                    }
                }
                itemBox.addView(delBtn)
            }

            val minusBtn = TextView(context).apply {
                text = "−"
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(dp(26), dp(26))
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(Color.parseColor("#311018"))
                }
                setOnClickListener {
                    if (item.quantity > 1) {
                        item.quantity -= 1
                        recalculateOrderTotals(ord)
                        refreshOrderCard()
                    } else if (ord.items.size > 1) {
                        ord.items.removeAt(idx)
                        recalculateOrderTotals(ord)
                        refreshOrderCard()
                    }
                }
            }
            itemBox.addView(minusBtn)

            val qtyTv = TextView(context).apply {
                text = "${item.quantity}"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(7), 0, dp(7), 0)
            }
            itemBox.addView(qtyTv)

            val plusBtn = TextView(context).apply {
                text = "+"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(dp(26), dp(26))
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(Color.parseColor("#064E3B"))
                }
                setOnClickListener {
                    item.quantity += 1
                    recalculateOrderTotals(ord)
                    refreshOrderCard()
                }
            }
            itemBox.addView(plusBtn)

            val infoCol = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                }
            }

            val nameTv = TextView(context).apply {
                text = "${item.name}${if (item.variant.isNotBlank()) " (${item.variant})" else ""}"
                setTextColor(Color.WHITE)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            }
            val priceTv = TextView(context).apply {
                text = "${nf.format(item.unitPrice)} د.ج × ${item.quantity} = ${nf.format(item.lineTotal)} د.ج"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 10f
                gravity = Gravity.END
            }
            infoCol.addView(nameTv)
            infoCol.addView(priceTv)
            itemBox.addView(infoCol)

            inlineConfirmationDrawer.addView(itemBox)
        }

        // 2. بيانات الزبون والعنوان والملاحظات (مع إمكانية التعديل المباشر)
        val custBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }

        if (!isEditingCustomerInfo) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val editBtn = TextView(context).apply {
                text = "✏️ تعديل البيانات"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(Color.parseColor("#0C4A6E"))
                }
                setOnClickListener {
                    isEditingCustomerInfo = true
                    onSetFocusable(true)
                    refreshOrderCard()
                }
            }

            val addrInfo = TextView(context).apply {
                val deliveryLabel = if (ord.deliveryType.contains("desk") || ord.deliveryType.contains("stop")) {
                    "🏢 مكتب التوصيل"
                } else {
                    "🏠 توصيل للمنزل"
                }
                text = buildString {
                    append("$deliveryLabel • ${ord.wilaya}")
                    if (ord.commune.isNotBlank()) append(" - ${ord.commune}")
                    if (ord.notes.isNotBlank()) append("\n📝 ملاحظة: ${ord.notes}")
                }
                setTextColor(Color.parseColor("#D4D4D8"))
                textSize = 10.5f
                gravity = Gravity.END
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                }
            }

            row.addView(editBtn)
            row.addView(addrInfo)
            custBox.addView(row)
        } else {
            val nameEd = createEditInput(ord.customerName, "اسم الزبون")
            val phoneEd = createEditInput(ord.customerPhone, "رقم الهاتف", InputType.TYPE_CLASS_PHONE)
            val wilayaEd = createEditInput(ord.wilaya, "الولاية")
            val communeEd = createEditInput(ord.commune, "البلدية / العنوان")
            val notesEd = createEditInput(ord.notes, "ملاحظة الطلبية")

            custBox.addView(nameEd)
            custBox.addView(phoneEd)
            custBox.addView(wilayaEd)
            custBox.addView(communeEd)
            custBox.addView(notesEd)

            val doneEditBtn = TextView(context).apply {
                text = "✔️ اعتماد تعديل العنوان والزبون"
                setTextColor(Color.BLACK)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(5)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#38BDF8"))
                }
                setOnClickListener {
                    ord.customerName = nameEd.text.toString().trim().ifEmpty { ord.customerName }
                    ord.customerPhone = phoneEd.text.toString().trim().ifEmpty { ord.customerPhone }
                    ord.wilaya = wilayaEd.text.toString().trim().ifEmpty { ord.wilaya }
                    ord.commune = communeEd.text.toString().trim()
                    ord.notes = notesEd.text.toString().trim()
                    isEditingCustomerInfo = false
                    hideSoftKeyboard()
                    onSetFocusable(false)
                    refreshOrderCard()
                }
            }
            custBox.addView(doneEditBtn)
        }
        inlineConfirmationDrawer.addView(custBox)

        // 3. التحكم بسعر التوصيل والإجمالي النهائي
        val shipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            }
        }

        fun makeShipStepBtn(title: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(7), dp(4), dp(7), dp(4))
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(4)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#1F1F23"))
                    setStroke(dp(1), Color.parseColor("#3F3F46"))
                }
                setOnClickListener { onClick() }
            }
        }

        shipRow.addView(makeShipStepBtn("-50") {
            ord.shippingCost = max(0, ord.shippingCost - 50)
            recalculateOrderTotals(ord)
            refreshOrderCard()
        })
        shipRow.addView(makeShipStepBtn("مجاني 0") {
            ord.shippingCost = 0
            recalculateOrderTotals(ord)
            refreshOrderCard()
        })
        shipRow.addView(makeShipStepBtn("+50") {
            ord.shippingCost += 50
            recalculateOrderTotals(ord)
            refreshOrderCard()
        })

        val shipLabel = TextView(context).apply {
            text = "🚚 التوصيل: ${nf.format(ord.shippingCost)} د.ج  |  الإجمالي: ${nf.format(ord.total)} د.ج"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        shipRow.addView(shipLabel)
        inlineConfirmationDrawer.addView(shipRow)

        // 4. زر الحفظ الفوري في قاعدة البيانات
        val saveBtn = TextView(context).apply {
            text = "💾 حفظ التعديلات وتحديث الطلبية فوراً"
            setTextColor(Color.BLACK)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#F59E0B"))
            }
            setOnClickListener {
                hideSoftKeyboard()
                onSetFocusable(false)
                isEditingCustomerInfo = false
                onSaveOrderDetails(ord)
                refreshOrderCard()
            }
        }
        inlineConfirmationDrawer.addView(saveBtn)
    }

    private fun createEditInput(value: String, hintStr: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        return EditText(context).apply {
            setText(value)
            hint = hintStr
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.WHITE)
            textSize = 11f
            inputType = type
            gravity = Gravity.END
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(Color.parseColor("#09090B"))
                setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
        }
    }

    private fun hideSoftKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
        } catch (_: Exception) {}
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
                start to cal.timeInMillis
            }
            "week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.add(Calendar.WEEK_OF_YEAR, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                start to cal.timeInMillis
            }
            "month" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                start to cal.timeInMillis
            }
            else -> 0L to Long.MAX_VALUE
        }
    }

    private fun getOrdersInSelectedTimePeriod(): List<MerchantOrderItem> {
        val all = currentPayload?.orders ?: emptyList()
        if (selectedTimeRange == "all") return all
        val (startMs, endMs) = computePeriodBounds(selectedTimeRange, selectedDateOffset)
        return all.filter { it.createdAtMs in startMs until endMs }
    }

    private fun refreshAllAnalyticsAndCards() {
        val p = currentPayload ?: return
        val nf = NumberFormat.getIntegerInstance(Locale.US)

        // 1. تحديث أزرار الفلتر الزمني (الكل | شهري | أسبوعي | يومي)
        for ((key, tab) in timeRangeTabs) {
            val active = key == selectedTimeRange
            tab.setTextColor(if (active) Color.BLACK else Color.WHITE)
            tab.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (active) Color.parseColor("#F59E0B") else Color.parseColor("#1F1F23"))
                if (!active) setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
        }

        dateResetTodayBtn.visibility = if (selectedDateOffset < 0) View.VISIBLE else View.GONE
        dateNextArrowBtn.alpha = if (selectedDateOffset < 0) 1f else 0.35f

        // 2. حساب المبيعات والمقارنة البصرية (اليوم مقابل البارحة، أو البارحة مقابل قبل البارحة...)
        val effectiveCompareRange = if (selectedTimeRange == "all") "day" else selectedTimeRange
        val (curStart, curEnd) = computePeriodBounds(effectiveCompareRange, selectedDateOffset)
        val (prevStart, prevEnd) = computePeriodBounds(effectiveCompareRange, selectedDateOffset - 1)

        var curSales = 0
        var curCount = 0
        var prevSales = 0

        for (o in p.orders) {
            if (o.status.lowercase() == "cancelled") continue
            if (o.createdAtMs in curStart until curEnd) {
                curSales += o.total
                curCount += 1
            } else if (o.createdAtMs in prevStart until prevEnd) {
                prevSales += o.total
            }
        }

        val dayFmt = SimpleDateFormat("dd/MM", Locale.US)
        val curDateStr = dayFmt.format(Date(curStart))

        val periodLabel: String
        val gmvHeader: String
        val vsLabel: String

        when (effectiveCompareRange) {
            "day" -> {
                when (selectedDateOffset) {
                    0 -> {
                        periodLabel = if (selectedTimeRange == "all") "♾️ كل الطلبات • مبيعات اليوم ($curDateStr)" else "📅 اليوم ($curDateStr)"
                        gmvHeader = "مبيعات اليوم (GMV)"
                        vsLabel = "مقابل البارحة"
                    }
                    -1 -> {
                        periodLabel = "📅 البارحة ($curDateStr)"
                        gmvHeader = "مبيعات البارحة (GMV)"
                        vsLabel = "مقابل قبل البارحة"
                    }
                    -2 -> {
                        periodLabel = "📅 قبل البارحة ($curDateStr)"
                        gmvHeader = "مبيعات قبل البارحة"
                        vsLabel = "مقابل اليوم السابق"
                    }
                    else -> {
                        val dAgo = abs(selectedDateOffset)
                        periodLabel = "📅 منذ $dAgo أيام ($curDateStr)"
                        gmvHeader = "مبيعات منذ $dAgo أيام"
                        vsLabel = "مقابل اليوم السابق"
                    }
                }
            }
            "week" -> {
                periodLabel = if (selectedDateOffset == 0) "📆 هذا الأسبوع ($curDateStr)" else "📆 أسبوع سابق ($curDateStr)"
                gmvHeader = if (selectedDateOffset == 0) "مبيعات الأسبوع" else "مبيعات أسبوع سابق"
                vsLabel = "مقابل الأسبوع السابق"
            }
            else -> {
                val mFmt = SimpleDateFormat("MM/yyyy", Locale.US)
                val mStr = mFmt.format(Date(curStart))
                periodLabel = "🗓️ شهر $mStr"
                gmvHeader = "مبيعات الشهر ($mStr)"
                vsLabel = "مقابل الشهر السابق"
            }
        }

        dateNavLabelText.text = periodLabel
        gmvTitleLabel.text = gmvHeader
        gmvValueText.text = "${nf.format(curSales)} د.ج"
        gmvOrdersBadge.text = "$curCount طلب"

        val diff = curSales - prevSales
        val pct = if (prevSales > 0) {
            ((diff.toDouble() / prevSales.toDouble()) * 100.0).toInt()
        } else if (curSales > 0) {
            100
        } else {
            0
        }

        if (diff >= 0) {
            val sign = if (diff > 0) "+" else ""
            gmvDeltaCompareText.text = "📈 $sign${nf.format(diff)} د.ج ($sign$pct%) $vsLabel (${nf.format(prevSales)})"
            gmvDeltaCompareText.setTextColor(Color.parseColor("#34D399"))
        } else {
            gmvDeltaCompareText.text = "📉 ${nf.format(diff)} د.ج ($pct%) $vsLabel (${nf.format(prevSales)})"
            gmvDeltaCompareText.setTextColor(Color.parseColor("#FDA4AF"))
        }

        val maxBarPx = dp(130)
        val minBarPx = dp(14)
        val maxVal = max(1, max(curSales, prevSales))
        val curW = max(minBarPx, ((curSales.toFloat() / maxVal.toFloat()) * maxBarPx).toInt())
        val prevW = max(minBarPx, ((prevSales.toFloat() / maxVal.toFloat()) * maxBarPx).toInt())

        gmvCurrentBar.layoutParams = (gmvCurrentBar.layoutParams as LayoutParams).apply { width = curW }
        gmvPrevBar.layoutParams = (gmvPrevBar.layoutParams as LayoutParams).apply { width = prevW }
        gmvCurrentBar.requestLayout()
        gmvPrevBar.requestLayout()

        // 3. تحديث عدادات المراحل الأربع حسب النطاق الزمني المختار
        val periodOrders = getOrdersInSelectedTimePeriod()
        var n = 0
        var prep = 0
        var ship = 0
        var deliv = 0
        var cod = 0

        for (o in periodOrders) {
            when (o.stage) {
                "new" -> { n++; if (o.status.lowercase() != "cancelled") cod += o.total }
                "preparing" -> { prep++; cod += o.total }
                "shipping" -> { ship++; cod += o.total }
                "delivered" -> { deliv++ }
            }
        }

        val totalNewAllTime = p.orders.count { it.stage == "new" }
        badgeText.text = totalNewAllTime.toString()
        badgeText.visibility = if (totalNewAllTime > 0) View.VISIBLE else View.GONE

        codValueText.text = "${nf.format(cod)} د.ج 💰"
        tabNewCount.text = "$n ⏳"
        tabPrepCount.text = "$prep 📦"
        tabShipCount.text = "$ship 🚚"
        tabDelivCount.text = "$deliv ✅"

        refreshStageHighlight()
        refreshOrderCard()
    }

    fun enterOpticalStealthMode() {
        mainHandler.post {
            closeInlineEditors()
            cockpitCard.visibility = View.GONE
            bubbleWrapper.alpha = 0.01f
            bubbleWrapper.layoutParams = LayoutParams(1, 1)
        }
    }

    fun exitOpticalStealthAndOpen() {
        mainHandler.post {
            visibility = View.VISIBLE
            bubbleWrapper.layoutParams = LayoutParams(dp(62), dp(62))
            bubbleWrapper.alpha = 1f
            selectedStage = "new"
            currentOrderIndex = 0
            refreshAllAnalyticsAndCards()
            cockpitCard.visibility = View.VISIBLE
        }
    }

    private fun recalculateCountsAndFollowOrder(orderId: String, targetStage: String) {
        selectedStage = targetStage
        val filtered = getFilteredOrders()
        val idx = filtered.indexOfFirst { it.id == orderId }
        currentOrderIndex = if (idx >= 0) idx else 0
        refreshAllAnalyticsAndCards()
    }

    private fun getFilteredOrders(): List<MerchantOrderItem> {
        return getOrdersInSelectedTimePeriod().filter { it.stage == selectedStage }
    }

    private fun getCurrentOrder(): MerchantOrderItem? {
        val list = getFilteredOrders()
        if (list.isEmpty()) return null
        val safeIdx = currentOrderIndex.coerceIn(0, list.size - 1)
        return list[safeIdx]
    }

    private fun refreshStageHighlight() {
        val pairs = listOf(
            Pair("new", tabNewBox),
            Pair("preparing", tabPrepBox),
            Pair("shipping", tabShipBox),
            Pair("delivered", tabDelivBox)
        )
        for ((key, box) in pairs) {
            val active = key == selectedStage
            box.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (active) Color.parseColor("#2A1B05") else Color.parseColor("#141418"))
                setStroke(dp(if (active) 2 else 1), if (active) Color.parseColor("#F59E0B") else Color.parseColor("#27272A"))
            }
        }
    }

    private fun refreshOrderCard() {
        val list = getFilteredOrders()
        if (list.isEmpty()) {
            pagerText.text = "0 / 0"
            orderRefText.text = "#---"
            orderTimeText.text = if (selectedTimeRange == "all") "لا توجد طلبات في هذا القسم" else "لا توجد طلبات لهذه الفترة (اضغط ♾️ الكل)"
            customerNameText.text = "👤 قائمة فارغة"
            customerPhoneText.text = "---"
            orderPriceText.text = "0 د.ج"
            wilayaBadgeText.text = "📍 ---"
            itemsSummaryText.text = "لا توجد سلع للعرض حالياً"
            itemsExpandHintBadge.text = "📋 التفاصيل ▾"
            btnDetailsToggle.text = "التفاصيل ▾"
            inlineConfirmationDrawer.visibility = View.GONE
            primaryActionBtn.alpha = 0.4f
            return
        }

        currentOrderIndex = currentOrderIndex.coerceIn(0, list.size - 1)
        val ord = list[currentOrderIndex]
        val nf = NumberFormat.getIntegerInstance(Locale.US)

        pagerText.text = "${currentOrderIndex + 1} / ${list.size}"
        orderRefText.text = ord.ref
        orderTimeText.text = "🕒 ${ord.relativeTime}"
        customerNameText.text = "👤 ${ord.customerName}"
        customerPhoneText.text = ord.customerPhone
        carrierBadgeText.text = ord.carrier
        orderPriceText.text = "${nf.format(ord.total)} د.ج"
        wilayaBadgeText.text = "📍 ${ord.wilaya}"
        itemsSummaryText.text = "📦 ${ord.itemsSummary}"
        itemsExpandHintBadge.text = if (isDetailsDrawerOpen) "▴ إخفاء" else "📋 التفاصيل ▾"
        btnDetailsToggle.text = if (isDetailsDrawerOpen) "إخفاء ▴" else "التفاصيل ▾"
        primaryActionBtn.alpha = 1f

        renderInlineConfirmationDrawer(ord)

        when (ord.stage) {
            "new" -> {
                primaryActionBtn.text = "⚡ قبول وتأكيد الطلب فوراً ⚡"
                primaryActionBtn.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#3B82F6"))
                ).apply { cornerRadius = dp(14).toFloat() }
            }
            "preparing" -> {
                primaryActionBtn.text = "🚚 تسليم لشركة التوصيل الآن"
                primaryActionBtn.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#D97706"), Color.parseColor("#F59E0B"))
                ).apply { cornerRadius = dp(14).toFloat() }
            }
            "shipping" -> {
                primaryActionBtn.text = "✅ تأكيد التسليم وتحصيل المبلغ"
                primaryActionBtn.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#059669"), Color.parseColor("#10B981"))
                ).apply { cornerRadius = dp(14).toFloat() }
            }
            else -> {
                primaryActionBtn.text = "✅ طلب مكتمل ومُسلم"
                primaryActionBtn.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#064E3B"))
                }
                primaryActionBtn.alpha = 0.7f
            }
        }
    }

    fun isCockpitOpen(): Boolean = cockpitCard.visibility == View.VISIBLE

    fun toggleCockpit(show: Boolean) {
        if (!show) closeInlineEditors()
        cockpitCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun focusNewOrdersTab() {
        mainHandler.post {
            selectedStage = "new"
            currentOrderIndex = 0
            refreshAllAnalyticsAndCards()
        }
    }

    fun updatePayload(payload: CockpitPayload) {
        mainHandler.post {
            // إذا كان التاجر يحرر عنوان زبون حالياً، نحتفظ بالتعديلات المفتوحة دون مقاطعته
            if (isEditingCustomerInfo) return@post

            for (o in payload.orders) {
                val overrideStage = localStageOverrides[o.id]
                if (overrideStage != null) {
                    o.stage = overrideStage
                }
            }

            currentPayload = payload

            storeTitleText.text = "✨ ${payload.storeName}"
            if (payload.isStoreOpen) {
                storeLockBtn.text = "🟢 مفتوح 🔓"
                storeLockBtn.setTextColor(Color.parseColor("#34D399"))
            } else {
                storeLockBtn.text = "🔴 مغلق 🔒"
                storeLockBtn.setTextColor(Color.parseColor("#FDA4AF"))
            }

            storeTabsRow.removeAllViews()
            for (st in payload.stores) {
                val isSelected = selectedStoreFilter == st.slug
                val pill = TextView(context).apply {
                    text = st.name
                    setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(6)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (isSelected) Color.parseColor("#F59E0B") else Color.parseColor("#18181B"))
                        setStroke(dp(1), Color.parseColor("#3F3F46"))
                    }
                    setOnClickListener {
                        selectedStoreFilter = st.slug
                        onSelectStore(st.slug)
                    }
                }
                storeTabsRow.addView(pill)
            }

            val isAllSelected = selectedStoreFilter == "all"
            val allPill = TextView(context).apply {
                text = "كل متاجري (${payload.stores.size})"
                setTextColor(if (isAllSelected) Color.BLACK else Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(5), dp(12), dp(5))
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (isAllSelected) Color.parseColor("#F59E0B") else Color.parseColor("#18181B"))
                }
                setOnClickListener {
                    selectedStoreFilter = "all"
                    onSelectStore("all")
                }
            }
            storeTabsRow.addView(allPill)

            refreshAllAnalyticsAndCards()
        }
    }
}
