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

class AppChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_ADDED ||
            intent.action == Intent.ACTION_PACKAGE_REMOVED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {

            val prefs = context.getSharedPreferences(
                "mdm_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", null)
                ?: return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Wait for package manager to settle
                    delay(2000)

                    val pm       = context.packageManager
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

                    // ── Save counts using commit() so data is written BEFORE broadcast ──
                    prefs.edit()
                        .putInt("last_total_apps",  totalApps)
                        .putInt("last_system_apps", systemApps)
                        .putInt("last_user_apps",   userApps)
                        .commit() // commit() blocks until written — safer than apply()

                    // ── Extra delay to guarantee prefs are saved ──
                    delay(500)

                    // ── Tell MainActivity to refresh UI ──
                    val uiIntent = Intent("com.sujan.mdm.APP_COUNT_UPDATED")
                    context.sendBroadcast(uiIntent)

                    // ── Sync full updated list to backend ──
                    RetrofitClient.instance.sendApps(apps)

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}