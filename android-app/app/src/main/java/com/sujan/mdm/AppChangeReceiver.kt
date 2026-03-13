package com.sujan.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AppChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action != Intent.ACTION_PACKAGE_ADDED &&
            action != Intent.ACTION_PACKAGE_REMOVED &&
            action != Intent.ACTION_PACKAGE_REPLACED) return

        val prefs    = context.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        // The package that was added/removed
        val changedPkg = intent.data?.schemeSpecificPart ?: return

        // Map intent action → log action string
        val logAction = when (action) {
            Intent.ACTION_PACKAGE_ADDED    -> "INSTALLED"
            Intent.ACTION_PACKAGE_REMOVED  -> "UNINSTALLED"
            Intent.ACTION_PACKAGE_REPLACED -> "INSTALLED"
            else                           -> return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait for package manager to settle
                delay(2000)

                val pm     = context.packageManager
                val client = OkHttpClient()

                // ── Resolve adminId — fetch from server if not cached ────────
                var adminId = prefs.getLong("admin_id", -1L)
                if (adminId == -1L) {
                    try {
                        val req = Request.Builder()
                            .url("https://mdm-project-production.up.railway.app/api/device-admin?deviceId=$deviceId")
                            .get()
                            .build()
                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string()
                        if (resp.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            adminId  = json.optLong("adminId", -1L)
                            if (adminId != -1L) {
                                prefs.edit().putLong("admin_id", adminId).apply()
                            }
                        }
                        resp.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // ── 1. Log the specific install/uninstall event ──────────────
                val appName: String
                val installSource: String

                if (logAction == "UNINSTALLED") {
                    // Package is already gone — can't query it anymore
                    appName       = changedPkg
                    installSource = "N/A"
                } else {
                    val appInfo = try {
                        pm.getApplicationInfo(changedPkg, 0)
                    } catch (e: Exception) { null }

                    appName = appInfo?.loadLabel(pm)?.toString() ?: changedPkg

                    installSource = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pm.getInstallSourceInfo(changedPkg)
                                .installingPackageName ?: "Unknown"
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getInstallerPackageName(changedPkg) ?: "Unknown"
                        }
                    } catch (e: Exception) { "Unknown" }
                }

                // POST activity log entry — works even if adminId was just fetched
                if (adminId != -1L) {
                    try {
                        val json = JSONObject().apply {
                            put("deviceId",      deviceId)
                            put("adminId",       adminId)
                            put("model",         Build.MODEL)
                            put("manufacturer",  Build.MANUFACTURER)
                            put("appName",       appName)
                            put("packageName",   changedPkg)
                            put("installSource", installSource)
                            put("action",        logAction)
                        }
                        val body = json.toString()
                            .toRequestBody("application/json".toMediaType())
                        val req = Request.Builder()
                            .url("https://mdm-project-production.up.railway.app/api/activity-log")
                            .post(body)
                            .build()
                        client.newCall(req).execute().close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // ── 2. Rebuild full app inventory and update counts ──────────
                val packages = pm.getInstalledPackages(0)

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
                                pm.getInstallSourceInfo(pkg.packageName)
                                    .installingPackageName ?: "Unknown"
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

                // commit() blocks until written — safer than apply()
                prefs.edit()
                    .putInt("last_total_apps",  totalApps)
                    .putInt("last_system_apps", systemApps)
                    .putInt("last_user_apps",   userApps)
                    .commit()

                delay(500)

                // Tell MainActivity to refresh UI
                val uiIntent = Intent("com.sujan.mdm.APP_COUNT_UPDATED")
                context.sendBroadcast(uiIntent)

                // Sync full updated inventory to backend
                RetrofitClient.instance.sendApps(apps)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}