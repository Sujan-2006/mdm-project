package com.sujan.mdm

import android.content.ComponentName
import android.content.Context
import android.app.admin.DevicePolicyManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val command = message.data["command"] ?: return

        when (command) {
            "ENFORCE_RESTRICTIONS" -> enforceRestrictionsNow()
        }
    }

    override fun onNewToken(token: String) {
        // Send updated FCM token to backend
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitClient.instance.updateFcmToken(deviceId, token)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun enforceRestrictionsNow() {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch latest blocked packages from server
                val response = RetrofitClient.instance.getRestrictedPackages(deviceId)
                if (response.isSuccessful) {
                    val blockedPackages = response.body() ?: emptyList()

                    // Apply restrictions immediately using DevicePolicyManager
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                            as DevicePolicyManager
                    val adminComponent = ComponentName(
                        applicationContext,
                        MyDeviceAdminReceiver::class.java
                    )

                    if (!dpm.isDeviceOwnerApp(packageName)) return@launch

                    val pm = packageManager
                    val allPackages = pm.getInstalledPackages(
                        android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
                    ).map { it.packageName }.toSet()

                    // Block packages
                    for (pkg in blockedPackages) {
                        if (pkg == packageName) continue
                        if (allPackages.contains(pkg)) {
                            try {
                                dpm.setApplicationHidden(adminComponent, pkg, true)
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    // Unblock packages not in blocked list
                    for (pkg in allPackages) {
                        if (pkg == packageName) continue
                        if (!blockedPackages.contains(pkg)) {
                            try {
                                dpm.setApplicationHidden(adminComponent, pkg, false)
                            } catch (e: Exception) { }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}