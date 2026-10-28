const fs = require("fs");

console.log("====================================================");
console.log("🛡️ ترقية كبسولة التاجر للاستيقاظ الخلفي وإشعارات شاشة القفل...");
console.log("====================================================");

// 1. ترقية AndroidManifest.xml بالأذونات وخصائص ForegroundService
const manifestPath = "app/src/main/AndroidManifest.xml";
let manifest = fs.readFileSync(manifestPath, "utf8");

const requiredPerms = [
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
  "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.USE_FULL_SCREEN_INTENT"
];

for (const perm of requiredPerms) {
  if (!manifest.includes(perm)) {
    manifest = manifest.replace(
      "<application",
      `    <uses-permission android:name="${perm}" />\n\n    <application`
    );
  }
}

if (!manifest.includes("foregroundServiceType")) {
  manifest = manifest.replace(
    /<service\s+android:name="\.MerchantCockpitService"\s+android:enabled="true"\s+android:exported="false"\s*\/>/,
    `<service
            android:name=".MerchantCockpitService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="dataSync|specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Real-time merchant order radar and instant wake-up notification service" />
        </service>`
  );
}

fs.writeFileSync(manifestPath, manifest, "utf8");
console.log("✅ 1/3: تم تحديث AndroidManifest.xml بأذونات ForegroundService وإشعارات شاشة القفل.");

// 2. ترقية MainActivity.kt لطلب إذن الإشعارات في أندرويد 13+ وتشغيل الخدمة الأمامية
const mainActPath = "app/src/main/java/com/dzmerchant/cockpit/MainActivity.kt";
let mainAct = fs.readFileSync(mainActPath, "utf8");

if (!mainAct.includes("POST_NOTIFICATIONS")) {
  mainAct = mainAct.replace(
    "override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)",
    `override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 5433)
                }
            } catch (_: Exception) {}
        }`
  );
}

if (!mainAct.includes("startForegroundService")) {
  mainAct = mainAct.replace(
    "startService(intent)",
    `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(intent)
            } catch (_: Exception) {
                startService(intent)
            }
        } else {
            startService(intent)
        }`
  );
}

const mainOpen = (mainAct.match(/\{/g) || []).length;
const mainClose = (mainAct.match(/\}/g) || []).length;
if (mainOpen !== mainClose) throw new Error("اختلال أقواس في MainActivity.kt!");
fs.writeFileSync(mainActPath, mainAct, "utf8");
console.log("✅ 2/3: تم تحديث MainActivity.kt لطلب إذن الإشعارات وتشغيل الخدمة المحمية.");

// 3. ترقية MerchantCockpitService.kt بالحارس الأمامي + النبض الخلفي + إشعارات شاشة القفل
const servicePath = "app/src/main/java/com/dzmerchant/cockpit/MerchantCockpitService.kt";
let svc = fs.readFileSync(servicePath, "utf8");

if (!svc.includes("ensureForegroundGuardian")) {
  // أ) إضافة متغير المجدول الخلفي ومستقبل الشاشة
  svc = svc.replace(
    "@Volatile private var isServiceAlive = true",
    `@Volatile private var isServiceAlive = true
    private var bgScheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var screenStateReceiver: android.content.BroadcastReceiver? = null`
  );

  // ب) حقن دوال الحارس الأمامي وإشعارات الطلبات الحرجة قبل onBind
  const guardianFunctions = `
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
                "🛍️ طلبية جديدة في $cleanStore • \${ord.total} د.ج"
            } else {
                "👑 طلبية جديدة وصلت $cleanStore الآن!"
            }

            val bodyText = if (ord != null) {
                "👤 \${ord.customerName} | 📍 \${ord.wilaya} | 📦 \${ord.itemsSummary}"
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
`;

  svc = svc.replace(
    "override fun onBind(intent: Intent?): IBinder? = null",
    guardianFunctions + "\n    override fun onBind(intent: Intent?): IBinder? = null"
  );

  // ج) تفعيل الحارس في onStartCommand و onCreate
  svc = svc.replace(
    "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {",
    "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        ensureForegroundGuardian()"
  );

  svc = svc.replace(
    "super.onCreate()\n        isServiceAlive = true",
    "super.onCreate()\n        isServiceAlive = true\n        ensureForegroundGuardian()"
  );

  // د) جعل WakeLock غير معدود المراجع + إضافة مجدول خلفي لا يتوقف عند قفل الشاشة
  svc = svc.replace(
    "wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, \"dzmerchant:CockpitWatchdog\")\n            wakeLock?.acquire()",
    "wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, \"dzmerchant:CockpitWatchdog\")?.apply { setReferenceCounted(false) }\n            if (wakeLock?.isHeld == false) wakeLock?.acquire()"
  );

  svc = svc.replace(
    "startLocalWakeupServer()\n        handler.post(syncRunnable)",
    `startLocalWakeupServer()
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
        } catch (_: Exception) {}`
  );

  // هـ) تعزيز wakeUpScreenAndPopup لتوقظ الشاشة لمدة 10 ثوانٍ وتعرض فوق القفل
  svc = svc.replace(
    "screenLock.acquire(5000)",
    "screenLock.acquire(10000)"
  );

  // و) إطلاق إشعار شاشة القفل الفوري عند وصول طلبية جديدة في triggerSync
  svc = svc.replace(
    "if (hasNewIncomingOrder || forceOpen) {\n                    handler.post { wakeUpScreenAndPopup() }\n                }",
    `if (hasNewIncomingOrder || forceOpen) {
                    val alertOrd = currentTopNewOrder ?: payload.orders.firstOrNull()
                    handler.post {
                        if (hasNewIncomingOrder) {
                            fireUrgentOrderNotification(alertOrd, payload.storeName)
                        }
                        wakeUpScreenAndPopup()
                    }
                }`
  );

  // ز) تنظيف المجدول والمستقبل في onDestroy
  svc = svc.replace(
    "isServiceAlive = false",
    `isServiceAlive = false
        try { bgScheduler?.shutdownNow() } catch (_: Exception) {}
        try { if (screenStateReceiver != null) unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}`
  );
}

const svcOpen = (svc.match(/\{/g) || []).length;
const svcClose = (svc.match(/\}/g) || []).length;
console.log(\`🔍 توازن أقواس MerchantCockpitService.kt: { = \${svcOpen} | } = \${svcClose}\`);
if (svcOpen !== svcClose) throw new Error("اختلال أقواس في MerchantCockpitService.kt!");

fs.writeFileSync(servicePath, svc, "utf8");
console.log("✅ 3/3: تم تحديث MerchantCockpitService.kt بالحارس الخلفي وإشعارات شاشة القفل!");
