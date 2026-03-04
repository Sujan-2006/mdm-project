package com.sujan.mdm

import android.content.Context
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
            val deviceId = prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"

            // Enroll
            RetrofitClient.instance.enroll(
                EnrollRequest(deviceId, "MDM_TOKEN_2024")
            )

            // Send device info
            val deviceInfo = DeviceInfoRequest(
                deviceId = deviceId,
                model = android.os.Build.MODEL,
                manufacturer = android.os.Build.MANUFACTURER,
                osVersion = android.os.Build.VERSION.RELEASE,
                sdkVersion = android.os.Build.VERSION.SDK_INT.toString(),
                uuid = deviceId,
                serial = "RESTRICTED"
            )
            RetrofitClient.instance.sendDeviceInfo(deviceInfo)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}