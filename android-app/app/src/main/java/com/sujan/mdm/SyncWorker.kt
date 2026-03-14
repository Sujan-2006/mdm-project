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
                    val location = getLastLocation()
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
        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                val fusedClient = LocationServices.getFusedLocationProviderClient(applicationContext)

                fusedClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        cont.resume(location)
                        return@addOnSuccessListener
                    }

                    val locationRequest = LocationRequest.Builder(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L
                    ).setMaxUpdates(1).build()

                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            fusedClient.removeLocationUpdates(this)
                            cont.resume(result.lastLocation)
                        }
                    }

                    try {
                        fusedClient.requestLocationUpdates(
                            locationRequest, callback, Looper.getMainLooper()
                        )
                        cont.invokeOnCancellation {
                            fusedClient.removeLocationUpdates(callback)
                        }
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }.addOnFailureListener {
                    cont.resume(null)
                }
            }
        }
    }
}