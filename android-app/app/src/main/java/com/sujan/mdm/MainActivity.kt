package com.sujan.mdm

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvTotalApps: TextView
    private lateinit var tvSystemApps: TextView
    private lateinit var tvUserApps: TextView
    private lateinit var btnEnroll: Button
    private lateinit var btnCollectInfo: Button
    private lateinit var btnSyncApps: Button
    private lateinit var etEnrollmentToken: EditText

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    // ── Receives broadcast from AppChangeReceiver and updates UI immediately ──
    private val appCountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Always run on UI thread to safely update TextViews
            runOnUiThread {
                restoreAppCounts()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize DevicePolicyManager
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE)
                as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // Initialize views
        tvStatus          = findViewById(R.id.tvStatus)
        tvDeviceInfo      = findViewById(R.id.tvDeviceInfo)
        tvSyncStatus      = findViewById(R.id.tvSyncStatus)
        tvTotalApps       = findViewById(R.id.tvTotalApps)
        tvSystemApps      = findViewById(R.id.tvSystemApps)
        tvUserApps        = findViewById(R.id.tvUserApps)
        btnEnroll         = findViewById(R.id.btnEnroll)
        btnCollectInfo    = findViewById(R.id.btnCollectInfo)
        btnSyncApps       = findViewById(R.id.btnSyncApps)
        etEnrollmentToken = findViewById(R.id.etEnrollmentToken)

        // Check enrollment status on startup
        checkEnrollmentStatus()

        // Check Device Owner status
        checkDeviceOwnerStatus()

        // ── Restore last saved app counts on startup ──
        restoreAppCounts()

        // ── Register broadcast receiver (works on all Android versions) ──
        val filter = IntentFilter("com.sujan.mdm.APP_COUNT_UPDATED")
        ContextCompat.registerReceiver(
            this,
            appCountReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Enroll button
        btnEnroll.setOnClickListener {
            val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
            val alreadyEnrolled = prefs.getBoolean("is_enrolled", false)
            if (alreadyEnrolled) {
                tvSyncStatus.text = "✅ Device already enrolled!"
                return@setOnClickListener
            }
            val token = etEnrollmentToken.text.toString().trim()
            if (token.isEmpty()) {
                tvSyncStatus.text = "Please enter enrollment token!"
                return@setOnClickListener
            }
            enrollDevice(token)
        }

        // Collect info button
        btnCollectInfo.setOnClickListener {
            val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
            val alreadyCollected = prefs.getBoolean("info_collected", false)
            if (alreadyCollected) {
                tvSyncStatus.text = "✅ Device info already sent!"
                return@setOnClickListener
            }
            collectDeviceInfo()
        }

        // Sync apps button
        btnSyncApps.setOnClickListener {
            syncAppInventory()
        }

        // Handle QR provisioning
        handleProvisioningIntent()

        // Schedule background sync
        scheduleBackgroundSync()
    }

    // ── KEY FIX: Every time app becomes visible, refresh counts from SharedPreferences ──
    // This means even if the broadcast was missed (app was closed), the count
    // will always be correct when you open or switch back to the app
    override fun onResume() {
        super.onResume()
        restoreAppCounts()
    }

    // ── Unregister receiver when app is destroyed ──
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(appCountReceiver)
    }

    // ── Read latest counts from SharedPreferences and update UI ──
    private fun restoreAppCounts() {
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val savedTotal  = prefs.getInt("last_total_apps",  0)
        val savedSystem = prefs.getInt("last_system_apps", 0)
        val savedUser   = prefs.getInt("last_user_apps",   0)

        if (savedTotal > 0) {
            tvTotalApps.text  = savedTotal.toString()
            tvSystemApps.text = savedSystem.toString()
            tvUserApps.text   = savedUser.toString()
            tvSyncStatus.text = """
                ✅ App inventory synced!
                
                📦 Total  : $savedTotal
                ⚙️ System : $savedSystem
                👤 User   : $savedUser
            """.trimIndent()
        }
    }

    private fun checkEnrollmentStatus() {
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val isEnrolled      = prefs.getBoolean("is_enrolled",    false)
        val isInfoCollected = prefs.getBoolean("info_collected", false)

        if (isEnrolled) {
            tvStatus.text = "🟢 Status: Enrolled ✅"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
            btnEnroll.isEnabled = false
            btnEnroll.alpha = 0.5f
            etEnrollmentToken.isEnabled = false
            etEnrollmentToken.hint = "Already enrolled"
        }

        if (isInfoCollected) {
            btnCollectInfo.isEnabled = false
            btnCollectInfo.alpha = 0.5f
            tvDeviceInfo.text = "✅ Device Info Already Collected!"
        }
    }

    private fun checkDeviceOwnerStatus() {
        val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val isEnrolled = prefs.getBoolean("is_enrolled", false)
        if (isDeviceOwner) {
            tvStatus.text = "🟢 Status: Device Owner Active"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
        } else if (!isEnrolled) {
            tvStatus.text = "⚪ Status: Not Enrolled"
        }
    }

    private fun enrollDevice(token: String) {
        lifecycleScope.launch {
            try {
                tvStatus.text     = "🔄 Status: Enrolling..."
                tvSyncStatus.text = "Connecting to server..."

                val response = RetrofitClient.instance.enroll(
                    EnrollRequest(deviceId, token)
                )

                if (response.isSuccessful) {
                    getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                        .edit().putBoolean("is_enrolled", true).apply()

                    tvStatus.text = "🟢 Status: Enrolled ✅"
                    tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    btnEnroll.isEnabled = false
                    btnEnroll.alpha = 0.5f
                    etEnrollmentToken.isEnabled = false
                    etEnrollmentToken.hint = "Already enrolled"

                    tvSyncStatus.text = """
                        ✅ Device enrolled successfully!
                        
                        Device ID : $deviceId
                        Token Used: $token
                        Server    : Connected ✅
                    """.trimIndent()
                } else {
                    val errorMsg = response.errorBody()?.string()
                        ?: "Unknown error"
                    if (errorMsg.contains("already enrolled")) {
                        getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                            .edit().putBoolean("is_enrolled", true).apply()
                        checkEnrollmentStatus()
                        tvSyncStatus.text = "✅ Device already enrolled!"
                    } else {
                        tvStatus.text = "🔴 Status: Enrollment Failed"
                        tvSyncStatus.text = "❌ $errorMsg"
                    }
                }
            } catch (e: Exception) {
                tvStatus.text     = "🔴 Status: Connection Error"
                tvSyncStatus.text = "❌ Error: ${e.message}\n\nCheck internet connection!"
            }
        }
    }

    private fun collectDeviceInfo() {
        lifecycleScope.launch {
            try {
                tvDeviceInfo.text = "Collecting device information..."

                val deviceInfo = DeviceInfoRequest(
                    deviceId     = deviceId,
                    model        = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    osVersion    = Build.VERSION.RELEASE,
                    sdkVersion   = Build.VERSION.SDK_INT.toString(),
                    uuid         = "RESTRICTED",
                    serial       = "RESTRICTED"
                )

                val isDeviceOwner = devicePolicyManager
                    .isDeviceOwnerApp(packageName)

                tvDeviceInfo.text = """
                    📱 Device ID    : $deviceId
                    📌 Model        : ${deviceInfo.model}
                    🏭 Manufacturer : ${deviceInfo.manufacturer}
                    🤖 Android      : ${deviceInfo.osVersion}
                    🔧 SDK          : ${deviceInfo.sdkVersion}
                    🔑 Serial       : ${deviceInfo.serial}
                    👑 Device Owner : ${if (isDeviceOwner) "Yes ✅" else "No ❌"}
                """.trimIndent()

                val response = RetrofitClient.instance
                    .sendDeviceInfo(deviceInfo)

                if (response.isSuccessful) {
                    getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                        .edit().putBoolean("info_collected", true).apply()
                    btnCollectInfo.isEnabled = false
                    btnCollectInfo.alpha = 0.5f
                    tvDeviceInfo.text = """
                        ✅ Device Info Collected!
                        
                        📌 Model        : ${deviceInfo.model}
                        🏭 Manufacturer : ${deviceInfo.manufacturer}
                        🤖 Android      : ${deviceInfo.osVersion}
                        🔧 SDK          : ${deviceInfo.sdkVersion}
                        🔑 Serial       : ${deviceInfo.serial}
                    """.trimIndent()
                    tvSyncStatus.text =
                        "✅ Device info sent to server successfully!"
                } else {
                    tvSyncStatus.text =
                        "❌ Failed to send: ${response.code()}"
                }
            } catch (e: Exception) {
                tvSyncStatus.text = "❌ Error: ${e.message}"
            }
        }
    }

    private fun syncAppInventory() {
        lifecycleScope.launch {
            try {
                tvSyncStatus.text = "📦 Collecting apps..."
                tvTotalApps.text  = "..."
                tvSystemApps.text = "..."
                tvUserApps.text   = "..."

                val pm       = packageManager
                val packages = pm.getInstalledPackages(0)

                val apps = packages.mapNotNull { pkg ->
                    val appInfo = pkg.applicationInfo
                        ?: return@mapNotNull null
                    AppItem(
                        deviceId      = deviceId,
                        appName       = appInfo.loadLabel(pm).toString(),
                        packageName   = pkg.packageName,
                        versionName   = pkg.versionName ?: "N/A",
                        versionCode   = pkg.versionCode,
                        isSystemApp   = (appInfo.flags and
                                ApplicationInfo.FLAG_SYSTEM) != 0,
                        installSource = try {
                            if (Build.VERSION.SDK_INT >=
                                Build.VERSION_CODES.R) {
                                pm.getInstallSourceInfo(pkg.packageName)
                                    .installingPackageName ?: "Unknown"
                            } else {
                                @Suppress("DEPRECATION")
                                pm.getInstallerPackageName(pkg.packageName)
                                    ?: "Unknown"
                            }
                        } catch (e: Exception) { "Unknown" }
                    )
                }

                val totalApps  = apps.size
                val systemApps = apps.count { it.isSystemApp }
                val userApps   = apps.count { !it.isSystemApp }

                // ── Update UI ──
                tvTotalApps.text  = totalApps.toString()
                tvSystemApps.text = systemApps.toString()
                tvUserApps.text   = userApps.toString()

                // ── Save counts so they persist ──
                getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                    .edit()
                    .putInt("last_total_apps",  totalApps)
                    .putInt("last_system_apps", systemApps)
                    .putInt("last_user_apps",   userApps)
                    .apply()

                val response = RetrofitClient.instance.sendApps(apps)
                if (response.isSuccessful) {
                    tvSyncStatus.text = """
                        ✅ App inventory synced!
                        
                        📦 Total  : $totalApps
                        ⚙️ System : $systemApps
                        👤 User   : $userApps
                    """.trimIndent()
                } else {
                    tvSyncStatus.text = "❌ Failed: ${response.code()}"
                }
            } catch (e: Exception) {
                tvSyncStatus.text = "❌ Error: ${e.message}"
            }
        }
    }

    private fun scheduleBackgroundSync() {
        val syncRequest = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(
                    androidx.work.NetworkType.CONNECTED)
                .build()
        ).setInitialDelay(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).build()

        androidx.work.WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "mdm_sync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
    }

    private fun handleProvisioningIntent() {
        if (intent.action ==
            "android.app.action.PROVISIONING_SUCCESSFUL" ||
            intent.action ==
            "android.app.action.PROFILE_PROVISIONING_COMPLETE") {

            val extras = intent.getBundleExtra(
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
            )
            val token = extras?.getString("enrollment_token")
                ?: "MDM_TOKEN_2024"

            tvSyncStatus.text = "🔄 Auto enrolling from QR..."
            enrollDevice(token)
        }
    }
}