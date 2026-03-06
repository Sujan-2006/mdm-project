package com.sujan.mdm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext
                .getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString(
                "device_id", "UNKNOWN") ?: "UNKNOWN"

            // Only sync app inventory every hour
            val pm = applicationContext.packageManager
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

            // Send only app inventory
            RetrofitClient.instance.sendApps(apps)
            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
    }
}