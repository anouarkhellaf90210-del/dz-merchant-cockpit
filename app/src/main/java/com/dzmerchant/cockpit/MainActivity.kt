package com.dzmerchant.cockpit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 5469
    private val RINGTONE_PICKER_REQ_CODE = 5470
    private lateinit var statusBadge: TextView
    private lateinit var storeUrlInput: EditText
    private lateinit var storeSlugInput: EditText

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
        val savedBaseUrl = prefs.getString("base_url", "https://my-private-store-app.vercel.app")
            ?: "https://my-private-store-app.vercel.app"
        val savedSlug = prefs.getString("store_slug", "") ?: ""

        if (intent?.action == "ACTION_PICK_RINGTONE") {
            openSystemRingtonePicker(prefs.getString("custom_ringtone_uri", null))
        }

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070B14"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val crownBadge = TextView(this).apply {
            text = "👑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                bottomMargin = dp(14)
            }
        }
        container.addView(crownBadge)

        val titleText = TextView(this).apply {
            text = "كبسولة القيادة الفورية للتاجر"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "تأكيد وتعديل الطلبيات • مقارنة المبيعات بالأيام • تخصيص النغمة"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(20)
            }
        }
        container.addView(subtitleText)

        statusBadge = TextView(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }
        container.addView(statusBadge)

        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }

        val urlLabel = TextView(this).apply {
            text = "🌐 رابط منصة المتجر (Vercel URL):"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
        }
        settingsCard.addView(urlLabel)

        storeUrlInput = EditText(this).apply {
            setText(savedBaseUrl)
            hint = "https://my-private-store-app.vercel.app"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(14)
            }
        }
        settingsCard.addView(storeUrlInput)

        val slugLabel = TextView(this).apply {
            text = "🏪 معرّف المتجر (Slug) - اتركه فارغاً للمتجر النشط:"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
        }
        settingsCard.addView(slugLabel)

        storeSlugInput = EditText(this).apply {
            setText(savedSlug)
            hint = "مثال: dzball أو اتركه فارغاً"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(14)
            }
        }
        settingsCard.addView(storeSlugInput)

        val pickRingtoneBtn = Button(this).apply {
            text = "🎵 اختيار نغمة رنين مخصصة من الهاتف"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setOnClickListener {
                openSystemRingtonePicker(prefs.getString("custom_ringtone_uri", null))
            }
        }
        settingsCard.addView(pickRingtoneBtn)

        container.addView(settingsCard)

        val launchBtn = Button(this).apply {
            text = "🚀 حفظ وتشغيل كبسولة التاجر الآن"
            setTextColor(Color.parseColor("#090D16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#F59E0B"),
                    Color.parseColor("#D97706")
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                saveSettingsAndStartBubble(minimizeAfterStart = true)
            }
        }
        container.addView(launchBtn)

        rootScroll.addView(container)
        setContentView(rootScroll)

        refreshPermissionStatus()
        if (canDrawOverlays()) {
            saveSettingsAndStartBubble(minimizeAfterStart = false)
        }
    }

    private fun openSystemRingtonePicker(currentUriString: String?) {
        try {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة رنين الطلبيات الجديدة")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                if (!currentUriString.isNullOrBlank()) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUriString))
                }
            }
            startActivityForResult(intent, RINGTONE_PICKER_REQ_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح منتقي النغمات في هذا الجهاز", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun refreshPermissionStatus() {
        if (canDrawOverlays()) {
            statusBadge.text = "✅ كبسولة التاجر جاهزة وتعمل فوق التطبيقات"
            statusBadge.setTextColor(Color.parseColor("#34D399"))
            statusBadge.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#064E3B"))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
        } else {
            statusBadge.text = "⚠️ اضغط على زر التشغيل لمنح صلاحية الظهور فوق التطبيقات"
            statusBadge.setTextColor(Color.parseColor("#FBBF24"))
            statusBadge.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#451A03"))
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
        }
    }

    private fun saveSettingsAndStartBubble(minimizeAfterStart: Boolean) {
        val rawUrl = storeUrlInput.text.toString().trim().trimEnd('/')
        val cleanUrl = if (rawUrl.isNotEmpty()) rawUrl else "https://my-private-store-app.vercel.app"
        val cleanSlug = storeSlugInput.text.toString().trim()

        getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("base_url", cleanUrl)
            .putString("store_slug", cleanSlug)
            .apply()

        if (!canDrawOverlays()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            }
            return
        }

        val serviceIntent = Intent(this, MerchantCockpitService::class.java).apply {
            action = "ACTION_START_OR_SYNC"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "👑 تم تفعيل كبسولة التاجر بنجاح!", Toast.LENGTH_SHORT).show()
        if (minimizeAfterStart) {
            moveTaskToBack(true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            refreshPermissionStatus()
            if (canDrawOverlays()) {
                saveSettingsAndStartBubble(minimizeAfterStart = true)
            }
        } else if (requestCode == RINGTONE_PICKER_REQ_CODE && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val ringtoneTitle = try {
                    RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "نغمة مخصصة"
                } catch (_: Exception) {
                    "نغمة مخصصة"
                }
                getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("ringtone_mode", "custom_uri")
                    .putString("custom_ringtone_uri", uri.toString())
                    .putString("custom_ringtone_title", ringtoneTitle)
                    .apply()

                val serviceIntent = Intent(this, MerchantCockpitService::class.java).apply {
                    action = "ACTION_RELOAD_RINGTONE"
                }
                startService(serviceIntent)
                Toast.makeText(this, "🎵 تم اعتماد النغمة: $ringtoneTitle", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }
    }
}
