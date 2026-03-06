package com.sujan.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        // Check Device Owner status
        checkDeviceOwnerStatus()

        // Enroll button
        btnEnroll.setOnClickListener {
            val token = etEnrollmentToken.text.toString().trim()
            if (token.isEmpty()) {
                tvSyncStatus.text = "Please enter enrollment token!"
                return@setOnClickListener
            }
            enrollDevice(token)
        }

        // Collect info button
        btnCollectInfo.setOnClickListener {
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

    private fun checkDeviceOwnerStatus() {
        val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
        if (isDeviceOwner) {
            tvStatus.text = "🟢 Status: Device Owner Active"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_light))
        } else {
            tvStatus.text = "⚪ Status: Not Enrolled"
        }
    }

    private fun enrollDevice(token: String) {
        lifecycleScope.launch {
            try {
                tvStatus.text = "🔄 Status: Enrolling..."
                tvSyncStatus.text = "Connecting to server..."

                val response = RetrofitClient.instance.enroll(
                    EnrollRequest(deviceId, token)
                )

                if (response.isSuccessful) {
                    tvStatus.text = "🟢 Status: Enrolled ✅"
                    tvSyncStatus.text = """
                        ✅ Device enrolled successfully!
                        
                        Device ID : $deviceId
                        Token Used: $token
                        Server    : Connected ✅
                    """.trimIndent()
                } else {
                    tvStatus.text = "🔴 Status: Enrollment Failed"
                    tvSyncStatus.text = "❌ Error: ${response.code()} - ${response.message()}"
                }
            } catch (e: Exception) {
                tvStatus.text = "🔴 Status: Connection Error"
                tvSyncStatus.text = "❌ Error: ${e.message}\n\nMake sure backend is running!"
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
                    uuid         = deviceId,
                    serial       = "RESTRICTED"
                )

                val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
                tvDeviceInfo.text = """
                    📱 Device ID    : $deviceId
                    📌 Model        : ${deviceInfo.model}
                    🏭 Manufacturer : ${deviceInfo.manufacturer}
                    🤖 Android      : ${deviceInfo.osVersion}
                    🔧 SDK          : ${deviceInfo.sdkVersion}
                    🔑 Serial       : ${deviceInfo.serial}
                    👑 Device Owner : ${if (isDeviceOwner) "Yes ✅" else "No ❌"}
                """.trimIndent()

                val response = RetrofitClient.instance.sendDeviceInfo(deviceInfo)
                if (response.isSuccessful) {
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

                val pm = packageManager
                val packages = pm.getInstalledPackages(0)

                val apps = packages.mapNotNull { pkg ->
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    AppItem(
                        deviceId      = deviceId,
                        appName       = appInfo.loadLabel(pm).toString(),
                        packageName   = pkg.packageName,
                        versionName   = pkg.versionName ?: "N/A",
                        versionCode   = pkg.versionCode,
                        isSystemApp   = (appInfo.flags and
                                ApplicationInfo.FLAG_SYSTEM) != 0,
                        installSource = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

                tvTotalApps.text  = totalApps.toString()
                tvSystemApps.text = systemApps.toString()
                tvUserApps.text   = userApps.toString()

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
            1, java.util.concurrent.TimeUnit.HOURS
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
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
