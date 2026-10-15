package com.dzmerchant.cockpit

import android.app.Activity
import android.content.Intent
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
    private var pendingStore: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingStore = intent?.data?.getQueryParameter("store") ?: "all"
        val forceOpen = intent?.data?.getQueryParameter("event") == "new_order" ||
            intent?.data?.getQueryParameter("open") == "1"

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
        }
    }

    private fun startCockpitService(store: String?, forceOpen: Boolean = false) {
        val intent = Intent(this, MerchantCockpitService::class.java).apply {
            putExtra("store", store ?: "all")
            putExtra("force_open", forceOpen)
        }
        startService(intent)
    }
}
