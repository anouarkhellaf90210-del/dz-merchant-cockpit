package com.dzmerchant.cockpit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.util.Locale

class MerchantCockpitOverlayView(
    context: Context,
    private val onPlayChime: () -> Unit,
    private val onHideStealth: () -> Unit,
    private val onCloseService: () -> Unit,
    private val onOrderAction: (String, String) -> Unit,
    private val onToggleStore: (String, Boolean) -> Unit,
    private val onSelectStore: (String) -> Unit
) : LinearLayout(context) {

    val bubbleWrapper: FrameLayout
    private val badgeText: TextView
    private val cockpitCard: LinearLayout
    private val storeTitleText: TextView
    private val storeLockBtn: TextView
    private val storeTabsRow: LinearLayout
    private val gmvValueText: TextView
    private val gmvOrdersBadge: TextView
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
    private val itemsSummaryText: TextView
    private val primaryActionBtn: TextView

    private var currentPayload: CockpitPayload? = null
    private var selectedStoreFilter: String = "all"
    private var selectedStage: String = "new"
    private var currentOrderIndex: Int = 0
    private val localStageOverrides = mutableMapOf<String, String>()

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
        val cardWidth = (screenWidth * 0.92).toInt().coerceAtMost(dp(370))

        cockpitCard = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#0D0D12"))
                setStroke(dp(1), Color.parseColor("#3F2E14"))
            }
            visibility = View.GONE
        }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(32), dp(32))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E11D48"))
            }
            setOnClickListener {
                toggleCockpit(false)
                onHideStealth()
            }
        }

        val minBtn = TextView(context).apply {
            text = "─"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(32), dp(32)).apply {
                leftMargin = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#27272A"))
            }
            setOnClickListener { toggleCockpit(false) }
        }

        storeLockBtn = TextView(context).apply {
            text = "🟢 مفتوح 🔓"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
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
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }

        val subHeader = TextView(context).apply {
            text = "كبسولة القيادة الفورية للتاجر"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 10f
            gravity = Gravity.END
        }

        titleCol.addView(storeTitleText)
        titleCol.addView(subHeader)

        headerRow.addView(closeBtn)
        headerRow.addView(minBtn)
        headerRow.addView(storeLockBtn)
        headerRow.addView(titleCol)
        cockpitCard.addView(headerRow)

        val storeScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        storeTabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }
        storeScroll.addView(storeTabsRow)
        cockpitCard.addView(storeScroll)

        val financeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val codCard = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(5)
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
            textSize = 10f
        }
        codValueText = TextView(context).apply {
            text = "0 د.ج 💰"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        codCard.addView(codLabel)
        codCard.addView(codValueText)

        val gmvCard = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(5)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }
        val gmvLabel = TextView(context).apply {
            text = "مبيعات اليوم (GMV)"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 10f
        }
        val gmvInnerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        gmvOrdersBadge = TextView(context).apply {
            text = "0 طلب"
            setTextColor(Color.parseColor("#818CF8"))
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1E1B4B"))
            }
        }
        gmvValueText = TextView(context).apply {
            text = "0 د.ج"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, 0, 0)
        }
        gmvInnerRow.addView(gmvOrdersBadge)
        gmvInnerRow.addView(gmvValueText)
        gmvCard.addView(gmvLabel)
        gmvCard.addView(gmvInnerRow)

        financeRow.addView(codCard)
        financeRow.addView(gmvCard)
        cockpitCard.addView(financeRow)

        val stagesRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        fun createStageTab(label: String, icon: String, stageKey: String): Pair<LinearLayout, TextView> {
            val box = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener {
                    selectedStage = stageKey
                    currentOrderIndex = 0
                    refreshStageHighlight()
                    refreshOrderCard()
                }
            }
            val countTv = TextView(context).apply {
                text = "0 $icon"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val titleTv = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 10f
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
        cockpitCard.addView(stagesRow)

        val orderDeck = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
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
                topMargin = dp(10)
            }
        }

        orderPriceText = TextView(context).apply {
            text = "0 د.ج"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        customerNameText = TextView(context).apply {
            text = "👤 لا توجد طلبات"
            setTextColor(Color.WHITE)
            textSize = 14f
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
                topMargin = dp(6)
                bottomMargin = dp(8)
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

        itemsSummaryText = TextView(context).apply {
            text = "---"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 11f
            gravity = Gravity.END
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#09090B"))
            }
        }
        orderDeck.addView(itemsSummaryText)

        primaryActionBtn = TextView(context).apply {
            text = "⚡ قبول وتأكيد الطلب فوراً ⚡"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
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
                    "new" -> Pair("preparing", "confirmed")
                    "preparing" -> Pair("shipping", "out_for_delivery")
                    "shipping" -> Pair("delivered", "delivered")
                    else -> null
                } ?: return@setOnClickListener

                val nextStage = nextStageAndStatus.first
                val nextStatus = nextStageAndStatus.second

                localStageOverrides[ord.id] = nextStage
                ord.stage = nextStage
                ord.status = nextStatus

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
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (isGreen) Color.parseColor("#064E3B") else Color.parseColor("#1F1F23"))
                    setStroke(dp(1), if (isGreen) Color.parseColor("#059669") else Color.parseColor("#3F3F46"))
                }
                setOnClickListener { onClick() }
            }
        }

        val btnDetails = createQuickActionBtn("التفاصيل ↗️") {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my-private-store-app.vercel.app/merchant/orders")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        }

        val btnWaybill = createQuickActionBtn("بوليصة 📋") {
            val ord = getCurrentOrder() ?: return@createQuickActionBtn
            val waybill = "بوليصة شحن ${ord.ref}\nالزبون: ${ord.customerName}\nالهاتف: ${ord.customerPhone}\nالولاية: ${ord.wilaya}\nالسلع: ${ord.itemsSummary}\nالمبلغ المستحق (COD): ${ord.total} د.ج"
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

        quickActionsRow.addView(btnDetails)
        quickActionsRow.addView(btnWaybill)
        quickActionsRow.addView(btnWhatsapp)
        quickActionsRow.addView(btnCall)
        orderDeck.addView(quickActionsRow)

        cockpitCard.addView(orderDeck)

        val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val btnHideStealth = TextView(context).apply {
            text = "👁️ إخفاء من الشاشة 🙈"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            setOnClickListener {
                toggleCockpit(false)
                onHideStealth()
            }
        }

        val btnTestSound = TextView(context).apply {
            text = "🔔 تجربة رنين الطلب 🔊"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
            setOnClickListener { onPlayChime() }
        }

        footerRow.addView(btnHideStealth)
        footerRow.addView(btnTestSound)
        cockpitCard.addView(footerRow)

        addView(cockpitCard)
        refreshStageHighlight()
    }

    private fun recalculateCountsAndFollowOrder(orderId: String, targetStage: String) {
        val p = currentPayload ?: return
        var n = 0
        var prep = 0
        var ship = 0
        var deliv = 0
        var cod = 0
        for (o in p.orders) {
            when (o.stage) {
                "new" -> { n++; cod += o.total }
                "preparing" -> { prep++; cod += o.total }
                "shipping" -> { ship++; cod += o.total }
                "delivered" -> { deliv++ }
            }
        }
        p.newCount = n
        p.preparingCount = prep
        p.shippingCount = ship
        p.deliveredCount = deliv
        p.codInStreet = cod

        val nf = NumberFormat.getIntegerInstance(Locale.US)
        badgeText.text = n.toString()
        badgeText.visibility = if (n > 0) View.VISIBLE else View.GONE
        codValueText.text = "${nf.format(cod)} د.ج 💰"
        tabNewCount.text = "$n ⏳"
        tabPrepCount.text = "$prep 📦"
        tabShipCount.text = "$ship 🚚"
        tabDelivCount.text = "$deliv ✅"

        selectedStage = targetStage
        val filtered = getFilteredOrders()
        val idx = filtered.indexOfFirst { it.id == orderId }
        currentOrderIndex = if (idx >= 0) idx else 0

        refreshStageHighlight()
        refreshOrderCard()
    }

    private fun getFilteredOrders(): List<MerchantOrderItem> {
        val all = currentPayload?.orders ?: emptyList()
        return all.filter { it.stage == selectedStage }
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
            orderTimeText.text = "لا توجد طلبات في هذا القسم"
            customerNameText.text = "👤 قائمة فارغة"
            customerPhoneText.text = "---"
            orderPriceText.text = "0 د.ج"
            wilayaBadgeText.text = "📍 ---"
            itemsSummaryText.text = "لا توجد سلع للعرض حالياً"
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
        itemsSummaryText.text = ord.itemsSummary
        primaryActionBtn.alpha = 1f

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
        cockpitCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun focusNewOrdersTab() {
        post {
            selectedStage = "new"
            currentOrderIndex = 0
            refreshStageHighlight()
            refreshOrderCard()
        }
    }

    fun updatePayload(payload: CockpitPayload) {
        post {
            var n = 0
            var prep = 0
            var ship = 0
            var deliv = 0
            var cod = 0
            for (o in payload.orders) {
                val overrideStage = localStageOverrides[o.id]
                if (overrideStage != null) {
                    o.stage = overrideStage
                }
                when (o.stage) {
                    "new" -> { n++; cod += o.total }
                    "preparing" -> { prep++; cod += o.total }
                    "shipping" -> { ship++; cod += o.total }
                    "delivered" -> { deliv++ }
                }
            }
            payload.newCount = n
            payload.preparingCount = prep
            payload.shippingCount = ship
            payload.deliveredCount = deliv
            payload.codInStreet = cod

            currentPayload = payload
            val nf = NumberFormat.getIntegerInstance(Locale.US)

            badgeText.text = payload.newCount.toString()
            badgeText.visibility = if (payload.newCount > 0) View.VISIBLE else View.GONE

            storeTitleText.text = "✨ ${payload.storeName}"
            if (payload.isStoreOpen) {
                storeLockBtn.text = "🟢 مفتوح 🔓"
                storeLockBtn.setTextColor(Color.parseColor("#34D399"))
            } else {
                storeLockBtn.text = "🔴 مغلق 🔒"
                storeLockBtn.setTextColor(Color.parseColor("#FDA4AF"))
            }

            gmvValueText.text = "${nf.format(payload.gmvToday)} د.ج"
            gmvOrdersBadge.text = "${payload.ordersTodayCount} طلب"
            codValueText.text = "${nf.format(payload.codInStreet)} د.ج 💰"

            tabNewCount.text = "${payload.newCount} ⏳"
            tabPrepCount.text = "${payload.preparingCount} 📦"
            tabShipCount.text = "${payload.shippingCount} 🚚"
            tabDelivCount.text = "${payload.deliveredCount} ✅"

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

            refreshStageHighlight()
            refreshOrderCard()
        }
    }
}
