package com.dzmerchant.cockpit

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

class MainActivity : Activity() {

    private var pendingStore: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isWatchingOverlay = false

    private val overlayWatcher = object : Runnable {
        override fun run() {
            if (!isWatchingOverlay) return
            if (checkOverlayPermission()) {
                isWatchingOverlay = false
                startCockpitService(pendingStore, forceOpen = true)
                finish()
            } else {
                handler.postDelayed(this, 600)
            }
        }
    }

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

        if (checkOverlayPermission()) {
            startCockpitService(pendingStore, forceOpen = forceOpen)
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
            text = "✅ الإصدار الرسمي v3.2.0 • توثيق المتجر المباشر"
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
            text = "اضغط الزر الأول لتوثيق الكبسولة كمصدر متجر رسمي وفتح إذن الظهور فوق التطبيقات مباشرة دون الحاجة لإزالة القيود يدوياً."
            textSize = 13f
            setTextColor(0xFFD4D4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 36)
        }

        val storeUnlockBtn = Button(this).apply {
            text = "🛡️ تفعيل الكبسولة بصلاحية المتجر الرسمي 👑"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF09090B.toInt())
            background = GradientDrawable().apply {
                setColor(0xFFF59E0B.toInt())
                cornerRadius = 28f
            }
            setPadding(30, 30, 30, 30)
            setOnClickListener {
                if (checkOverlayPermission()) {
                    startCockpitService(pendingStore, forceOpen = true)
                    finish()
                } else if (!isInstalledAsOfficialStoreSource()) {
                    upgradeToOfficialStoreSource()
                } else {
                    openOverlaySettings()
                }
            }
        }

        val pipBtn = Button(this).apply {
            text = "🚀 أو تعويم نافذة التاجر المصغرة فوراً بدون إعدادات"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF18181B.toInt())
                cornerRadius = 24f
                setStroke(2, 0xFFF59E0B.toInt())
            }
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        enterPictureInPictureMode(
                            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 10)).build()
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        layout.addView(badge)
        layout.addView(title)
        layout.addView(desc)
        layout.addView(storeUnlockBtn)
        layout.addView(TextView(this).apply { height = 24 })
        layout.addView(pipBtn)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (checkOverlayPermission()) {
            isWatchingOverlay = false
            startCockpitService(pendingStore, forceOpen = true)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isWatchingOverlay = false
        handler.removeCallbacks(overlayWatcher)
    }

    private fun isInstalledAsOfficialStoreSource(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            val info = packageManager.getInstallSourceInfo(packageName)
            info.packageSource == PackageInstaller.PACKAGE_SOURCE_STORE
        } catch (_: Exception) {
            false
        }
    }

    private fun upgradeToOfficialStoreSource() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                try {
                    val allowIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    Toast.makeText(
                        this,
                        "اضغط «سماح» مرة واحدة لتوثيق كبسولة التاجر كمصدر متجر رسمي وفتح الإذن مباشرة",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(allowIntent)
                    return
                } catch (_: Exception) {}
            }
        }

        Thread {
            try {
                val apkFile = File(applicationInfo.sourceDir)
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= 33) {
                    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                FileInputStream(apkFile).use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val callbackIntent = Intent(this, StoreSourceReceiver::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(this, sessionId, callbackIntent, flags)
                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (_: Exception) {
                handler.post { openOverlaySettings() }
            }
        }.start()
    }

    private fun openOverlaySettings() {
        isWatchingOverlay = true
        handler.removeCallbacks(overlayWatcher)
        handler.postDelayed(overlayWatcher, 600)
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
            startService(intent)
        } catch (_: Exception) {}
    }
}
