package com.sujan.mdm

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
            val adminId  = prefs.getLong("admin_id", -1L)

            val pm       = applicationContext.packageManager
            val packages = withContext(Dispatchers.IO) { pm.getInstalledPackages(0) }

            // ── Build current app list ────────────────────────────────────
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

            // ── Load previously saved data ────────────────────────────────
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

            // ── Detect installs and uninstalls ────────────────────────────
            val newlyInstalled   = currentPackages - savedPackages
            val newlyUninstalled = savedPackages   - currentPackages

            // ── Post activity log — SKIP on first run ─────────────────────
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
                            .url("https://mdm-project-production.up.railway.app/api/activity-log")
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
                            .url("https://mdm-project-production.up.railway.app/api/activity-log")
                            .post(body).build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            // ── Save current package list and name map ────────────────────
            val newNameMap = apps.joinToString("||") { "${it.packageName}=${it.appName}" }
            prefs.edit()
                .putString("saved_package_list", currentPackages.joinToString(","))
                .putString("saved_app_name_map", newNameMap)
                .apply()

            // ── Ping backend to update lastSeen timestamp ─────────────────
            try {
                val pingReq = Request.Builder()
                    .url("https://mdm-project-production.up.railway.app/api/device-ping?deviceId=$deviceId")
                    .post("".toRequestBody(null))
                    .build()
                OkHttpClient().newCall(pingReq).execute().close()
            } catch (e: Exception) { e.printStackTrace() }

            // ── Send location to backend ──────────────────────────────────
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

            // ── Sync full inventory to backend ────────────────────────────
            RetrofitClient.instance.sendApps(apps)

            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
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
            android.util.Log.w("SyncWorker", "Location permission not granted — skipping")
            return null
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)

        // ── Step 1: try cached lastLocation (instant, no battery cost) ──
        val cached = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { loc ->
                        android.util.Log.d("SyncWorker", "Cached location: $loc")
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }
        if (cached != null) return cached

        // ── Step 2: request fresh location — try LOW_POWER (cell/WiFi) first ──
        // LOW_POWER works indoors without GPS, much faster than HIGH_ACCURACY
        android.util.Log.d("SyncWorker", "No cached location — requesting fresh (LOW_POWER)...")
        val fresh = withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_LOW_POWER, 1000L
                ).setMaxUpdates(1)
                    .setMinUpdateIntervalMillis(0L)
                    .build()

                val handlerThread = android.os.HandlerThread("loc-worker-${System.currentTimeMillis()}")
                handlerThread.start()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        fusedClient.removeLocationUpdates(this)
                        handlerThread.quitSafely()
                        val loc = result.lastLocation
                        android.util.Log.d("SyncWorker", "Fresh LOW_POWER location: $loc")
                        if (cont.isActive) cont.resume(loc)
                    }
                }
                try {
                    fusedClient.requestLocationUpdates(locationRequest, callback, handlerThread.looper)
                    cont.invokeOnCancellation {
                        fusedClient.removeLocationUpdates(callback)
                        handlerThread.quitSafely()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SyncWorker", "LOW_POWER request failed: ${e.message}")
                    handlerThread.quitSafely()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (fresh != null) return fresh

        // ── Step 3: fallback — try BALANCED_POWER as last resort ──
        android.util.Log.d("SyncWorker", "LOW_POWER timed out — trying BALANCED_POWER fallback...")
        return withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L
                ).setMaxUpdates(1)
                    .setMinUpdateIntervalMillis(0L)
                    .build()

                val handlerThread = android.os.HandlerThread("loc-worker-bal-${System.currentTimeMillis()}")
                handlerThread.start()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        fusedClient.removeLocationUpdates(this)
                        handlerThread.quitSafely()
                        val loc = result.lastLocation
                        android.util.Log.d("SyncWorker", "BALANCED location: $loc")
                        if (cont.isActive) cont.resume(loc)
                    }
                }
                try {
                    fusedClient.requestLocationUpdates(locationRequest, callback, handlerThread.looper)
                    cont.invokeOnCancellation {
                        fusedClient.removeLocationUpdates(callback)
                        handlerThread.quitSafely()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SyncWorker", "BALANCED request failed: ${e.message}")
                    handlerThread.quitSafely()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}