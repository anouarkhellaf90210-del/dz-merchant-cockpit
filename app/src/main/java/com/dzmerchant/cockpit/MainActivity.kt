package com.dzmerchant.cockpit

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var pendingStore: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingStore = intent?.data?.getQueryParameter("store") ?: "all"
        val forceOpen = intent?.data?.getQueryParameter("event") == "new_order" ||
            intent?.data?.getQueryParameter("open") == "1"

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 102)
                }
            } catch (_: Exception) {}
        }

        // تشغيل خدمة كبسولة التاجر وجسر 49211 فوراً في جميع الحالات بدون قيود
        startCockpitService(pendingStore, forceOpen = forceOpen)

        if (checkOverlayPermission()) {
            finish()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
            setBackgroundColor(0xFF09090B.toInt())
        }

        val badge = TextView(this).apply {
            text = "✅ رادار التاجر وجرس الطلبيات نشط الآن"
            textSize = 12f
            setTextColor(0xFF34D399.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                setColor(0xFF052E16.toInt())
                cornerRadius = 40f
                setStroke(2, 0xFF10B981.toInt())
            }
        }

        val title = TextView(this).apply {
            text = "كبسولة القيادة الفورية للتاجر 👑"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFBBF24.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 12)
        }

        val desc = TextView(this).apply {
            text = "اضغط الزر بالأسفل لتعويم كبسولة التاجر فوق الشاشة فوراً بنقرة واحدة واستقبال الطلبيات الحية بالصوت والاهتزاز."
            textSize = 13f
            setTextColor(0xFFD4D4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 40)
        }

        val pipBtn = Button(this).apply {
            text = "🚀 تعويم كبسولة التاجر بنقرة واحدة 👑"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF09090B.toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF59E0B.toInt())
                cornerRadius = 28f
            }
            setPadding(30, 30, 30, 30)
            setOnClickListener {
                startCockpitService(pendingStore, forceOpen = true)
                if (checkOverlayPermission()) {
                    finish()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val params = PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 10))
                            .build()
                        enterPictureInPictureMode(params)
                    } catch (_: Exception) {
                        moveTaskToBack(true)
                    }
                } else {
                    moveTaskToBack(true)
                }
            }
        }

        val classicBtn = Button(this).apply {
            text = "✨ أو تفعيل الأيقونة الدائرية الكلاسيكية (اختياري)"
            textSize = 11f
            setTextColor(0xFFA1A1AA.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF18181B.toInt())
                cornerRadius = 24f
                setStroke(2, 0xFF27272A.toInt())
            }
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        layout.addView(badge)
        layout.addView(title)
        layout.addView(desc)
        layout.addView(pipBtn)
        layout.addView(TextView(this).apply { height = 24 })
        layout.addView(classicBtn)

        setContentView(layout)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!checkOverlayPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 10)).build()
                )
            } catch (_: Exception) {}
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startCockpitService(store: String?, forceOpen: Boolean = false) {
        val intent = Intent(this, MerchantCockpitService::class.java).apply {
            putExtra("store", store ?: "all")
            putExtra("force_open", forceOpen)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            try { startService(intent) } catch (_: Exception) {}
        }
    }
}
