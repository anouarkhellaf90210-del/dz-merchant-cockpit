package com.dzmerchant.cockpit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val OVERLAY_REQ_CODE = 5432
    private val RINGTONE_REQ_CODE = 5433
    private var pendingStore: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 5433)
                }
            } catch (_: Exception) {}
        }

        if (intent?.action == "ACTION_PICK_RINGTONE") {
            openPhoneRingtonePicker()
            return
        }

        pendingStore = intent?.data?.getQueryParameter("store") ?: "all"
        val forceOpen = intent?.data?.getQueryParameter("event") == "new_order" ||
            intent?.data?.getQueryParameter("open") == "1" ||
            intent?.action == Intent.ACTION_MAIN

        if (checkOverlayPermission()) {
            startCockpitService(pendingStore, forceOpen = forceOpen)
            finish()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(0xFF09090B.toInt())
        }

        val title = TextView(this).apply {
            text = "كبسولة القيادة الفورية للتاجر 👑"
            textSize = 19f
            setTextColor(0xFFFBBF24.toInt())
            gravity = Gravity.CENTER
        }

        val desc = TextView(this).apply {
            text = "يرجى منح إذن «الظهور فوق التطبيقات» لتتمكن كبسولة قيادة المتجر من الطفو واستقبال الطلبات الحية فوق شاشتك."
            textSize = 13f
            setTextColor(0xFFA1A1AA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 50)
        }

        val btn = Button(this).apply {
            text = "منح الإذن وتشغيل كبسولة التاجر 👑"
            setBackgroundColor(0xFFF59E0B.toInt())
            setTextColor(0xFF000000.toInt())
            setOnClickListener { requestOverlayPermission() }
        }

        layout.addView(title)
        layout.addView(desc)
        layout.addView(btn)

        setContentView(layout)
    }

    private fun openPhoneRingtonePicker() {
        try {
            val prefs = getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
            val currentUri = prefs.getString("custom_ringtone_uri", null)
            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة رنين الطلبات للكبسولة")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                if (!currentUri.isNullOrBlank()) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
                }
            }
            startActivityForResult(pickerIntent, RINGTONE_REQ_CODE)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح منتقي النغمات", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_REQ_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQ_CODE) {
            if (checkOverlayPermission()) {
                startCockpitService(pendingStore, forceOpen = true)
                finish()
            } else {
                Toast.makeText(this, "يلزم تفعيل الإذن لتشغيل كبسولة التاجر", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == RINGTONE_REQ_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (uri != null) {
                    val title = try {
                        RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "نغمة الهاتف"
                    } catch (_: Exception) {
                        "نغمة الهاتف"
                    }
                    getSharedPreferences("dz_merchant_cockpit_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("ringtone_mode", "custom_uri")
                        .putString("custom_ringtone_uri", uri.toString())
                        .putString("custom_ringtone_title", title)
                        .apply()

                    Toast.makeText(this, "🎵 تم اعتماد النغمة: $title", Toast.LENGTH_SHORT).show()
                    val svcIntent = Intent(this, MerchantCockpitService::class.java).apply {
                        putExtra("force_open", true)
                        putExtra("play_chime", true)
                    }
                    startService(svcIntent)
                }
            }
            finish()
        }
    }

    private fun startCockpitService(store: String?, forceOpen: Boolean = false) {
        val intent = Intent(this, MerchantCockpitService::class.java).apply {
            putExtra("store", store ?: "all")
            putExtra("force_open", forceOpen)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(intent)
            } catch (_: Exception) {
                startService(intent)
            }
        } else {
            startService(intent)
        }
    }

    private fun syncDynamicPickupStageIcons(root: android.view.View) {
        val allTextViews = mutableListOf<android.widget.TextView>()
        fun collect(v: android.view.View) {
            if (v is android.widget.TextView) {
                allTextViews.add(v)
            } else if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    collect(v.getChildAt(i))
                }
            }
        }
        collect(root)

        var isPickupActive = false
        for (tv in allTextViews) {
            if (tv.visibility == android.view.View.VISIBLE) {
                val t = tv.text?.toString() ?: ""
                if (
                    t.contains("استلام شخصي") ||
                    t.contains("استلام بالمحل") ||
                    t.contains("استلام وسيط") ||
                    t.contains("بطاقة استلام") ||
                    t.contains("بانتظار المستلم") ||
                    t.contains("والدفع بالمحل") ||
                    t.contains("تجهيز الطلب بالمحل")
                ) {
                    isPickupActive = true
                    break
                }
            }
        }

        for (tv in allTextViews) {
            val raw = tv.text?.toString() ?: ""
            val trimmed = raw.trim()
            if (isPickupActive) {
                when (trimmed) {
                    "جديد" -> if (raw != "طلب بالمحل") tv.text = "طلب بالمحل"
                    "تجهيز" -> if (raw != "تجهيز بالمحل") tv.text = "تجهيز بالمحل"
                    "في الطريق" -> if (raw != "جاهز بالمحل") tv.text = "جاهز بالمحل"
                    "تم التسليم" -> if (raw != "تم الاستلام") tv.text = "تم الاستلام"
                    "🚚" -> tv.text = "🏪"
                    "✅" -> tv.text = "🤝"
                    else -> {
                        if (raw.contains("🚚") && raw.any { it.isDigit() }) {
                            tv.text = raw.replace("🚚", "🏪")
                        } else if (raw.contains("✅") && raw.any { it.isDigit() } && !raw.contains("تم")) {
                            tv.text = raw.replace("✅", "🤝")
                        }
                    }
                }
            } else {
                when (trimmed) {
                    "طلب بالمحل" -> if (raw != "جديد") tv.text = "جديد"
                    "تجهيز بالمحل" -> if (raw != "تجهيز") tv.text = "تجهيز"
                    "جاهز بالمحل" -> if (raw != "في الطريق") tv.text = "في الطريق"
                    "تم الاستلام" -> if (raw != "تم التسليم") tv.text = "تم التسليم"
                    "🏪" -> tv.text = "🚚"
                    "🤝" -> tv.text = "✅"
                    else -> {
                        if (raw.contains("🏪") && raw.any { it.isDigit() } && !raw.contains("استلام") && !raw.contains("المحل")) {
                            tv.text = raw.replace("🏪", "🚚")
                        } else if (raw.contains("🤝") && raw.any { it.isDigit() } && !raw.contains("تم")) {
                            tv.text = raw.replace("🤝", "✅")
                        }
                    }
                }
            }
        }
    }

}
