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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var btnViewInventory: Button
    private lateinit var etEnrollmentToken: EditText

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private val appCountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAppCountsNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent      = ComponentName(this, MyDeviceAdminReceiver::class.java)

        tvStatus          = findViewById(R.id.tvStatus)
        tvDeviceInfo      = findViewById(R.id.tvDeviceInfo)
        tvSyncStatus      = findViewById(R.id.tvSyncStatus)
        tvTotalApps       = findViewById(R.id.tvTotalApps)
        tvSystemApps      = findViewById(R.id.tvSystemApps)
        tvUserApps        = findViewById(R.id.tvUserApps)
        btnEnroll         = findViewById(R.id.btnEnroll)
        btnCollectInfo    = findViewById(R.id.btnCollectInfo)
        btnSyncApps       = findViewById(R.id.btnSyncApps)
        btnViewInventory  = findViewById(R.id.btnViewInventory)
        etEnrollmentToken = findViewById(R.id.etEnrollmentToken)

        checkEnrollmentStatus()
        checkDeviceOwnerStatus()
        restoreAppCounts()

        val prefs = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("is_enrolled", false) &&
            prefs.getLong("admin_id", -1L) == -1L) {
            fetchAndSaveAdminId()
        }

        val filter = IntentFilter("com.sujan.mdm.APP_COUNT_UPDATED")
        ContextCompat.registerReceiver(
            this, appCountReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        btnEnroll.setOnClickListener {
            val p = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
            if (p.getBoolean("is_enrolled", false)) {
                tvSyncStatus.text = "✅ Device already enrolled!"; return@setOnClickListener
            }
            val token = etEnrollmentToken.text.toString().trim()
            if (token.isEmpty()) {
                tvSyncStatus.text = "Please enter enrollment token!"; return@setOnClickListener
            }
            enrollDevice(token)
        }

        btnCollectInfo.setOnClickListener {
            val p = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
            if (p.getBoolean("info_collected", false)) {
                tvSyncStatus.text = "✅ Device info already sent!"; return@setOnClickListener
            }
            collectDeviceInfo()
        }

        btnSyncApps.setOnClickListener { syncAppInventory() }

        // ── Open App Inventory Screen ──
        btnViewInventory.setOnClickListener {
            startActivity(Intent(this, AppInventoryActivity::class.java))
        }

        handleProvisioningIntent()
        scheduleBackgroundSync()
    }

    override fun onResume() {
        super.onResume()
        refreshAppCountsNow()
    }

    private fun refreshAppCountsNow() {
        lifecycleScope.launch {
            val pm       = packageManager
            val packages = withContext(Dispatchers.IO) { pm.getInstalledPackages(0) }
            val total    = packages.size
            val system   = packages.count { pkg ->
                (pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            }
            val user = total - system

            tvTotalApps.text  = total.toString()
            tvSystemApps.text = system.toString()
            tvUserApps.text   = user.toString()

            if (total > 0) {
                tvSyncStatus.text = "✅ App inventory synced!\n\n📦 Total  : $total\n⚙️ System : $system\n👤 User   : $user"
                getSharedPreferences("mdm_prefs", MODE_PRIVATE).edit()
                    .putInt("last_total_apps",  total)
                    .putInt("last_system_apps", system)
                    .putInt("last_user_apps",   user)
                    .apply()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(appCountReceiver)
    }

    private fun restoreAppCounts() {
        val prefs       = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val savedTotal  = prefs.getInt("last_total_apps",  0)
        val savedSystem = prefs.getInt("last_system_apps", 0)
        val savedUser   = prefs.getInt("last_user_apps",   0)
        if (savedTotal > 0) {
            tvTotalApps.text  = savedTotal.toString()
            tvSystemApps.text = savedSystem.toString()
            tvUserApps.text   = savedUser.toString()
            tvSyncStatus.text = "✅ App inventory synced!\n\n📦 Total  : $savedTotal\n⚙️ System : $savedSystem\n👤 User   : $savedUser"
        }
    }

    private fun checkEnrollmentStatus() {
        val prefs           = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
        val isEnrolled      = prefs.getBoolean("is_enrolled",    false)
        val isInfoCollected = prefs.getBoolean("info_collected", false)
        if (isEnrolled) {
            tvStatus.text = "🟢 Status: Enrolled ✅"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
            btnEnroll.isEnabled         = false
            btnEnroll.alpha             = 0.5f
            etEnrollmentToken.isEnabled = false
            etEnrollmentToken.hint      = "Already enrolled"
        }
        if (isInfoCollected) {
            btnCollectInfo.isEnabled = false
            btnCollectInfo.alpha     = 0.5f
            tvDeviceInfo.text        = "✅ Device Info Already Collected!"
        }
    }

    private fun checkDeviceOwnerStatus() {
        val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
        val isEnrolled    = getSharedPreferences("mdm_prefs", MODE_PRIVATE)
            .getBoolean("is_enrolled", false)
        if (isDeviceOwner) {
            tvStatus.text = "🟢 Status: Device Owner Active"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
        } else if (!isEnrolled) {
            tvStatus.text = "⚪ Status: Not Enrolled"
        }
    }

    private fun fetchAndSaveAdminId() {
        lifecycleScope.launch {
            try {
                val res = RetrofitClient.instance.getAdminIdForDevice(deviceId)
                if (res.isSuccessful) {
                    val id = res.body()?.adminId ?: return@launch
                    getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                        .edit().putLong("admin_id", id).apply()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun enrollDevice(token: String) {
        lifecycleScope.launch {
            try {
                tvStatus.text     = "🔄 Status: Enrolling..."
                tvSyncStatus.text = "Connecting to server..."
                val response = RetrofitClient.instance.enroll(EnrollRequest(deviceId, token))
                if (response.isSuccessful) {
                    getSharedPreferences("mdm_prefs", MODE_PRIVATE).edit()
                        .putBoolean("is_enrolled", true).apply()
                    fetchAndSaveAdminId()
                    tvStatus.text = "🟢 Status: Enrolled ✅"
                    tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    btnEnroll.isEnabled         = false
                    btnEnroll.alpha             = 0.5f
                    etEnrollmentToken.isEnabled = false
                    etEnrollmentToken.hint      = "Already enrolled"
                    tvSyncStatus.text = "✅ Device enrolled successfully!\n\nDevice ID : $deviceId\nToken Used: $token\nServer    : Connected ✅"
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                    if (errorMsg.contains("already enrolled")) {
                        getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                            .edit().putBoolean("is_enrolled", true).apply()
                        fetchAndSaveAdminId()
                        checkEnrollmentStatus()
                        tvSyncStatus.text = "✅ Device already enrolled!"
                    } else {
                        tvStatus.text     = "🔴 Status: Enrollment Failed"
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
                tvDeviceInfo.text = "📱 Device ID    : $deviceId\n📌 Model        : ${deviceInfo.model}\n🏭 Manufacturer : ${deviceInfo.manufacturer}\n🤖 Android      : ${deviceInfo.osVersion}\n🔧 SDK          : ${deviceInfo.sdkVersion}"
                val response = RetrofitClient.instance.sendDeviceInfo(deviceInfo)
                if (response.isSuccessful) {
                    getSharedPreferences("mdm_prefs", MODE_PRIVATE)
                        .edit().putBoolean("info_collected", true).apply()
                    btnCollectInfo.isEnabled = false
                    btnCollectInfo.alpha     = 0.5f
                    tvSyncStatus.text = "✅ Device info sent to server successfully!"
                } else {
                    tvSyncStatus.text = "❌ Failed to send: ${response.code()}"
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
                val packages = withContext(Dispatchers.IO) { pm.getInstalledPackages(0) }
                val apps = packages.mapNotNull { pkg ->
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    AppItem(
                        deviceId      = deviceId,
                        appName       = appInfo.loadLabel(pm).toString(),
                        packageName   = pkg.packageName,
                        versionName   = pkg.versionName ?: "N/A",
                        versionCode   = pkg.versionCode,
                        isSystemApp   = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        installSource = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                pm.getInstallSourceInfo(pkg.packageName).installingPackageName ?: "Unknown"
                            } else {
                                @Suppress("DEPRECATION")
                                pm.getInstallerPackageName(pkg.packageName) ?: "Unknown"
                            }
                        } catch (e: Exception) { "Unknown" }
                    )
                }
                val totalApps  = apps.size
                val systemApps = apps.count { it.isSystemApp }
                val userApps   = apps.count { !it.isSystemApp }
                tvTotalApps.text  = totalApps.toString()
                tvSystemApps.text = systemApps.toString()
                tvUserApps.text   = userApps.toString()
                getSharedPreferences("mdm_prefs", MODE_PRIVATE).edit()
                    .putInt("last_total_apps",  totalApps)
                    .putInt("last_system_apps", systemApps)
                    .putInt("last_user_apps",   userApps)
                    .apply()
                val response = RetrofitClient.instance.sendApps(apps)
                if (response.isSuccessful) {
                    tvSyncStatus.text = "✅ App inventory synced!\n\n📦 Total  : $totalApps\n⚙️ System : $systemApps\n👤 User   : $userApps"
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
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
        ).setInitialDelay(15, java.util.concurrent.TimeUnit.MINUTES).build()

        androidx.work.WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "mdm_sync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
    }

    private fun handleProvisioningIntent() {
        if (intent.action == "android.app.action.PROVISIONING_SUCCESSFUL" ||
            intent.action == "android.app.action.PROFILE_PROVISIONING_COMPLETE") {
            val extras = intent.getBundleExtra("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
            val token  = extras?.getString("enrollment_token") ?: "MDM_TOKEN_2024"
            tvSyncStatus.text = "🔄 Auto enrolling from QR..."
            enrollDevice(token)
        }
    }
}