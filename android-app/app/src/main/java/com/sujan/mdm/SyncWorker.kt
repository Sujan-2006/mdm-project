package com.sujan.mdm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs    = applicationContext
                .getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"
            val adminId  = prefs.getLong("admin_id", -1L)

            val pm       = applicationContext.packageManager
            val packages = pm.getInstalledPackages(0)

            // ── Build current app list ────────────────────────────────────
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

            // ── Compare with previously saved package list ────────────────
            val currentPackages = apps.map { it.packageName }.toSet()

            // Load previously saved package list from SharedPrefs
            val savedPackagesRaw = prefs.getString("saved_package_list", "") ?: ""
            val savedPackages    = if (savedPackagesRaw.isEmpty()) emptySet()
            else savedPackagesRaw.split(",").toSet()

            // Detect installs and uninstalls
            val newlyInstalled   = currentPackages - savedPackages
            val newlyUninstalled = savedPackages   - currentPackages

            // ── Post activity log for each change ────────────────────────
            if (adminId != -1L &&
                (newlyInstalled.isNotEmpty() || newlyUninstalled.isNotEmpty())) {

                val client = OkHttpClient()

                // Log installed apps
                for (pkg in newlyInstalled) {
                    val appItem = apps.find { it.packageName == pkg }
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
                    try {
                        val body = json.toString()
                            .toRequestBody("application/json".toMediaType())
                        val req  = Request.Builder()
                            .url("https://mdm-project-production.up.railway.app/api/activity-log")
                            .post(body)
                            .build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Log uninstalled apps
                for (pkg in newlyUninstalled) {
                    val json = JSONObject().apply {
                        put("deviceId",      deviceId)
                        put("adminId",       adminId)
                        put("model",         Build.MODEL)
                        put("manufacturer",  Build.MANUFACTURER)
                        put("appName",       pkg)   // name not available after uninstall
                        put("packageName",   pkg)
                        put("installSource", "N/A")
                        put("action",        "UNINSTALLED")
                    }
                    try {
                        val body = json.toString()
                            .toRequestBody("application/json".toMediaType())
                        val req  = Request.Builder()
                            .url("https://mdm-project-production.up.railway.app/api/activity-log")
                            .post(body)
                            .build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // ── Save current package list for next comparison ─────────────
            prefs.edit()
                .putString("saved_package_list", currentPackages.joinToString(","))
                .apply()

            // ── Sync full inventory to backend ────────────────────────────
            RetrofitClient.instance.sendApps(apps)

            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
    }
}