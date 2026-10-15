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
    private val onOrderAction: (orderId: String, nextStatus: String) -> Unit,
    private val onToggleStore: (storeId: String, nextOpen: Boolean) -> Unit,
    private val onSelectStore: (storeSlug: String) -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    val bubbleWrapper: FrameLayout
    private val badgeText: TextView
    private val onlineDot: View

    private val cockpitCard: LinearLayout
    private val storeTitleText: TextView
    private val storeLockBtn: TextView
    private val storeTabsRow: LinearLayout

    private val gmvValueText: TextView
    private val gmvOrdersBadge: TextView
    private val codValueText: TextView

    private val tabNewCount: TextView
    private val tabPrepCount: TextView
    private val tabShipCount: TextView
    private val tabDelivCount: TextView

    private val tabNewBox: LinearLayout
    private val tabPrepBox: LinearLayout
    private val tabShipBox: LinearLayout
    private val tabDelivBox: LinearLayout

    private val orderRefText: TextView
    private val orderTimeText: TextView
    private val pagerText: TextView
    private val customerNameText: TextView
    private val customerPhoneText: TextView
    private val carrierBadgeText: TextView
    private val orderPriceText: TextView
    private val wilayaBadgeText: TextView
    private val itemsSummaryText: TextView
    private val primaryActionBtn: TextView

    private var currentPayload: CockpitPayload? = null
    private var selectedStage: String = "new"
    private var currentOrderIndex: Int = 0
    private var selectedStoreFilter: String = "all"

    init {
        // 1. الفقاعة العائمة الملكية للتاجر (54dp) مع العداد الأحمر
        bubbleWrapper = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(58), dp(58)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }

        val avatarCircle = FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(50), dp(50)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
            val crownIcon = TextView(context).apply {
                text = "👑"
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            addView(crownIcon)
        }
        bubbleWrapper.addView(avatarCircle)

        // النقطة الخضراء لحالة المتجر
        onlineDot = View(context).apply {
            layoutParams = LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(4)
                bottomMargin = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
                setStroke(dp(2), Color.parseColor("#09090B"))
            }
        }
        bubbleWrapper.addView(onlineDot)

        // الشارة الحمراء لعدد الطلبات الجديدة (24)
        badgeText = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(1), dp(5), dp(1))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E11D48"))
                setStroke(dp(1), Color.parseColor("#09090B"))
            }
        }
        bubbleWrapper.addView(badgeText)
        addView(bubbleWrapper)

        // 2. بطاقة قمرة القيادة الموسعة (مطابقة للصورة 1000045298.jpg)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.94).toInt().coerceAtMost(dp(390))

        cockpitCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(62)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#F70D0D11"))
                setStroke(dp(1), Color.parseColor("#4D854D0E"))
            }
        }

        // --- أ. صف الهيدر (الإغلاق، التصغير، حالة المتجر، اسم المتجر) ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E11D48"))
            }
            setOnClickListener {
                toggleCockpit(false)
                onCloseService()
            }
        }

        val minBtn = TextView(context).apply {
            text = "─"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
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
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#064E3B"))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            setOnClickListener {
                currentPayload?.let { p ->
                    onToggleStore(p.storeId, !p.isStoreOpen)
                }
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
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

        // --- ب. شريط المتاجر المتعددة ---
        val storeScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
        storeTabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        storeScroll.addView(storeTabsRow)
        cockpitCard.addView(storeScroll)

        // --- ج. بطاقتا السيولة النقدية (GMV + COD) ---
        val financeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        // بطاقة السيولة بالشارع (يسار)
        val codCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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

        // بطاقة مبيعات اليوم (يمين)
        val gmvCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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
            orientation = LinearLayout.HORIZONTAL
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

        // --- د. تبويبات الفلترة الأربعة ---
        val stagesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        fun createStageTab(label: String, icon: String, stageKey: String): Pair<LinearLayout, TextView> {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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

        // --- هـ. بطاقة فرز الطلب التفاعلية (Order Triage Deck) ---
        val orderDeck = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
        }

        // شريط الترقيم والمرجع
        val deckTopRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val prevBtn = TextView(context).apply {
            text = "<"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
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
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
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
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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

        // معلومات الزبون والسعر
        val custRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
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
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        custRow.addView(orderPriceText)
        custRow.addView(customerNameText)
        orderDeck.addView(custRow)

        // صف الهاتف والشريحة والولاية
        val subCustRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        }

        wilayaBadgeText = TextView(context).apply {
            text = "📍 الجزائر"
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
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        carrierBadgeText = TextView(context).apply {
            text = "موبيليس"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
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

        // سطر السلع
        itemsSummaryText = TextView(context).apply {
            text = "---"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 11f
            gravity = Gravity.END
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#09090B"))
            }
        }
        orderDeck.addView(itemsSummaryText)

        // زر الإجراء الرئيسي (قبول وتأكيد الطلب فوراً)
        primaryActionBtn = TextView(context).apply {
            text = "⚡ قبول وتأكيد الطلب فوراً ⚡"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
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
                val nextStatus = when (ord.stage) {
                    "new" -> "confirmed"
                    "preparing" -> "out_for_delivery"
                    "shipping" -> "delivered"
                    else -> ""
                }
                if (nextStatus.isNotEmpty()) {
                    onOrderAction(ord.id, nextStatus)
                }
            }
        }
        orderDeck.addView(primaryActionBtn)

        // شريط الإجراءات الأربعة السريعة (اتصال، واتساب، بوليصة، التفاصيل)
        val quickActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        fun createQuickActionBtn(title: String, isGreen: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = title
                setTextColor(if (isGreen) Color.parseColor("#34D399") else Color.parseColor("#E4E4E7"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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

        // --- و. شريط التحكم السفلي (إخفاء من الشاشة + تجربة رنين الطلب) ---
        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val btnHideStealth = TextView(context).apply {
            text = "👁️ إخفاء من الشاشة 🙈"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
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
                primaryActionBtn.visibility = View.VISIBLE
            }
            "preparing" -> {
                primaryActionBtn.text = "🚚 تسليم لشركة التوصيل الآن"
                primaryActionBtn.visibility = View.VISIBLE
            }
            "shipping" -> {
                primaryActionBtn.text = "✅ تأكيد التسليم وتحصيل المبلغ"
                primaryActionBtn.visibility = View.VISIBLE
            }
            else -> {
                primaryActionBtn.text = "✅ طلب مكتمل ومُسلم"
                primaryActionBtn.alpha = 0.5f
            }
        }
    }

    fun isCockpitOpen(): Boolean = cockpitCard.visibility == View.VISIBLE

    fun toggleCockpit(show: Boolean) {
        cockpitCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun updatePayload(payload: CockpitPayload) {
        post {
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

            // بناء أزرار تبديل المتاجر
            storeTabsRow.removeAllViews()
            for (st in payload.stores) {
                val isSelected = selectedStoreFilter == st.slug
                val pill = TextView(context).apply {
                    text = st.name
                    setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
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
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
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
