package com.sujan.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.location.Location
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs    = applicationContext.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"

            // ── Resolve adminId — fetch from server if not saved locally ──
            var adminId = prefs.getLong("admin_id", -1L)
            if (adminId == -1L) {
                try {
                    val res = RetrofitClient.instance.getAdminIdForDevice(deviceId)
                    if (res.isSuccessful) {
                        val fetchedId = res.body()?.adminId ?: -1L
                        if (fetchedId != -1L) {
                            adminId = fetchedId
                            prefs.edit().putLong("admin_id", fetchedId).apply()
                            android.util.Log.d("SyncWorker", "Fetched and saved adminId: $fetchedId")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SyncWorker", "Failed to fetch adminId: ${e.message}")
                }
            }

            val pm = applicationContext.packageManager

            // ── Step 1: Fetch blocked packages from server FIRST ──────────
            // We need this before building app list so we can include hidden apps
            var blockedPackages = emptyList<String>()
            try {
                val response = RetrofitClient.instance.getRestrictedPackages(deviceId)
                if (response.isSuccessful) {
                    blockedPackages = response.body() ?: emptyList()
                    android.util.Log.d("SyncWorker", "Blocked packages from server: $blockedPackages")
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncWorker", "Failed to fetch restrictions: ${e.message}")
            }

            // ── Step 2: Enable all system apps (fixes fully managed mode) ──────
            enableAllSystemApps()

            // ── Step 3: Enforce restrictions immediately ──────────────────────
            enforceRestrictions(blockedPackages)

            // ── Step 3: Build app list (use getInstalledPackages normally) ─
            // After enforcing restrictions, get ALL packages including hidden ones
            // by using MATCH_UNINSTALLED_PACKAGES flag
            val packages = withContext(Dispatchers.IO) {
                pm.getInstalledPackages(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }

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

            val currentPackages = apps.map { it.packageName }.toSet()

            // ── Step 4: Load previously saved package list ────────────────
            val savedPackagesRaw = prefs.getString("saved_package_list", "") ?: ""
            val isFirstRun       = savedPackagesRaw.isEmpty()
            val savedPackages    = if (isFirstRun) emptySet()
            else savedPackagesRaw.split(",").toSet()

            val savedNameMapRaw = prefs.getString("saved_app_name_map", "") ?: ""
            val savedNameMap    = mutableMapOf<String, String>()
            if (savedNameMapRaw.isNotEmpty()) {
                savedNameMapRaw.split("||").forEach { entry ->
                    val idx = entry.indexOf('=')
                    if (idx > 0) savedNameMap[entry.substring(0, idx)] = entry.substring(idx + 1)
                }
            }

            // ── Step 5: Detect REAL installs/uninstalls ───────────────────
            // Exclude blocked packages — they are hidden/unhidden, not installed/uninstalled
            val blockedSet       = blockedPackages.toSet()
            val newlyInstalled   = (currentPackages - savedPackages) - blockedSet
            val newlyUninstalled = (savedPackages - currentPackages) - blockedSet

            // ── Step 6: Post activity log ─────────────────────────────────
            if (!isFirstRun && adminId != -1L &&
                (newlyInstalled.isNotEmpty() || newlyUninstalled.isNotEmpty())) {

                val client = OkHttpClient()

                for (pkg in newlyInstalled) {
                    val appItem = apps.find { it.packageName == pkg }
                    try {
                        val json = JSONObject().apply {
                            put("deviceId",      deviceId)
                            put("adminId",       adminId)
                            put("model",         Build.MODEL)
                            put("manufacturer",  Build.MANUFACTURER)
                            put("appName",       appItem?.appName ?: pkg)
                            put("packageName",   pkg)
                            put("installSource", appItem?.installSource ?: "Unknown")
                            put("action",        "INSTALLED")
                        }
                        val body = json.toString().toRequestBody("application/json".toMediaType())
                        val req  = Request.Builder()
                            .url("https://mdm-project-5042.onrender.com/api/activity-log")
                            .post(body).build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (pkg in newlyUninstalled) {
                    val knownName = savedNameMap[pkg] ?: pkg
                    try {
                        val json = JSONObject().apply {
                            put("deviceId",      deviceId)
                            put("adminId",       adminId)
                            put("model",         Build.MODEL)
                            put("manufacturer",  Build.MANUFACTURER)
                            put("appName",       knownName)
                            put("packageName",   pkg)
                            put("installSource", "N/A")
                            put("action",        "UNINSTALLED")
                        }
                        val body = json.toString().toRequestBody("application/json".toMediaType())
                        val req  = Request.Builder()
                            .url("https://mdm-project-5042.onrender.com/api/activity-log")
                            .post(body).build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            // ── Step 7: Save current package list and name map ────────────
            val newNameMap = apps.joinToString("||") { "${it.packageName}=${it.appName}" }
            prefs.edit()
                .putString("saved_package_list", currentPackages.joinToString(","))
                .putString("saved_app_name_map", newNameMap)
                .apply()

            // ── Step 8: Ping backend to update lastSeen ───────────────────
            try {
                val pingReq = Request.Builder()
                    .url("https://mdm-project-5042.onrender.com/api/device-ping?deviceId=$deviceId")
                    .post("".toRequestBody(null))
                    .build()
                OkHttpClient().newCall(pingReq).execute().close()
            } catch (e: Exception) { e.printStackTrace() }

            // ── Step 9: Send location ─────────────────────────────────────
            if (adminId != -1L) {
                try {
                    android.util.Log.d("SyncWorker", "Fetching location for deviceId=$deviceId adminId=$adminId")
                    val location = getLastLocation()
                    android.util.Log.d("SyncWorker", "Location result: $location")
                    if (location != null) {
                        RetrofitClient.instance.sendLocation(
                            com.sujan.mdm.LocationRequest(
                                deviceId     = deviceId,
                                adminId      = adminId,
                                latitude     = location.latitude,
                                longitude    = location.longitude,
                                accuracy     = location.accuracy,
                                model        = Build.MODEL,
                                manufacturer = Build.MANUFACTURER
                            )
                        )
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            // ── Step 10: Sync full inventory to backend ───────────────────
            RetrofitClient.instance.sendApps(apps)

            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun enableAllSystemApps() {
        val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(
            applicationContext, MyDeviceAdminReceiver::class.java)
        if (!dpm.isDeviceOwnerApp(applicationContext.packageName)) return
        val pm = applicationContext.packageManager
        val packages = pm.getInstalledPackages(
            android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
        for (pkg in packages) {
            if (pkg.packageName == applicationContext.packageName) continue
            try {
                dpm.enableSystemApp(adminComponent, pkg.packageName)
            } catch (e: Exception) {
                // Some packages cannot be enabled — ignore
            }
        }
    }

    private fun enforceRestrictions(blockedPackages: List<String>) {
        val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(
            applicationContext, MyDeviceAdminReceiver::class.java)

        val isDeviceOwner = dpm.isDeviceOwnerApp(applicationContext.packageName)
        android.util.Log.d("SyncWorker", "isDeviceOwner=$isDeviceOwner")

        if (!isDeviceOwner) {
            android.util.Log.w("SyncWorker", "Not device owner — saving blocked list to prefs only")
            applicationContext.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
                .edit().putString("blocked_packages", blockedPackages.joinToString(",")).apply()
            return
        }

        val pm = applicationContext.packageManager
        // Use MATCH_UNINSTALLED_PACKAGES to get ALL packages including currently hidden ones
        val allPackages = pm.getInstalledPackages(
            android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
        ).map { it.packageName }.toSet()

        // Hide every blocked package
        for (pkg in blockedPackages) {
            if (pkg == applicationContext.packageName) continue
            if (allPackages.contains(pkg)) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, true)
                    android.util.Log.d("SyncWorker", "Hidden: $pkg")
                } catch (e: Exception) {
                    android.util.Log.e("SyncWorker", "Failed to hide $pkg: ${e.message}")
                }
            }
        }

        // Unhide every package that is NOT in the blocked list
        // This is the key fix — always unhide anything not blocked, regardless of previous state
        for (pkg in allPackages) {
            if (pkg == applicationContext.packageName) continue
            if (!blockedPackages.contains(pkg)) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, false)
                } catch (e: Exception) {
                    // Ignore — some system packages throw exceptions, that's fine
                }
            }
        }

        // Save current blocked list to prefs
        applicationContext.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
            .edit().putString("blocked_packages", blockedPackages.joinToString(",")).apply()
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastLocation(): Location? {
        val fineOk = androidx.core.content.ContextCompat.checkSelfPermission(
            applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseOk = androidx.core.content.ContextCompat.checkSelfPermission(
            applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!fineOk && !coarseOk) {
            android.util.Log.w("SyncWorker", "Location permission not granted")
            return null
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)
        val cached = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { loc ->
                        android.util.Log.d("SyncWorker", "FusedLocation cached: $loc")
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }
        if (cached != null) {
            android.util.Log.d("SyncWorker", "Using cached FusedLocation")
            return cached
        }

        android.util.Log.d("SyncWorker", "FusedLocation unavailable — trying LocationManager directly...")
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE)
                            as android.location.LocationManager

                    val networkEnabled = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                    val gpsEnabled     = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)

                    android.util.Log.d("SyncWorker", "NetworkProvider=$networkEnabled GPSProvider=$gpsEnabled")

                    if (networkEnabled) {
                        val last = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (last != null) {
                            android.util.Log.d("SyncWorker", "LocationManager NETWORK last known: ${last.latitude}, ${last.longitude}")
                            if (cont.isActive) cont.resume(last)
                            return@suspendCancellableCoroutine
                        }
                    }
                    if (gpsEnabled) {
                        val last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        if (last != null) {
                            android.util.Log.d("SyncWorker", "LocationManager GPS last known: ${last.latitude}, ${last.longitude}")
                            if (cont.isActive) cont.resume(last)
                            return@suspendCancellableCoroutine
                        }
                    }

                    val handlerThread = android.os.HandlerThread("loc-lm-${System.currentTimeMillis()}")
                    handlerThread.start()

                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            android.util.Log.d("SyncWorker", "LocationManager fresh: ${location.latitude}, ${location.longitude}")
                            lm.removeUpdates(this)
                            handlerThread.quitSafely()
                            if (cont.isActive) cont.resume(location)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }

                    try {
                        val provider = when {
                            networkEnabled -> android.location.LocationManager.NETWORK_PROVIDER
                            gpsEnabled     -> android.location.LocationManager.GPS_PROVIDER
                            else           -> null
                        }
                        if (provider == null) {
                            android.util.Log.w("SyncWorker", "No location provider available")
                            handlerThread.quitSafely()
                            if (cont.isActive) cont.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        android.util.Log.d("SyncWorker", "Requesting LocationManager update via $provider")
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, handlerThread.looper)
                        cont.invokeOnCancellation {
                            lm.removeUpdates(listener)
                            handlerThread.quitSafely()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SyncWorker", "LocationManager request failed: ${e.message}")
                        handlerThread.quitSafely()
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }
}