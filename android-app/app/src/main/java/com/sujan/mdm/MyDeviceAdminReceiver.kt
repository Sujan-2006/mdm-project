package com.sujan.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "Device Admin Enabled ✅",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "Device Admin Disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onProfileProvisioningComplete(
        context: Context,
        intent: Intent
    ) {
        super.onProfileProvisioningComplete(context, intent)

        // Enable all system apps so the device feels normal to the user
        // Admin can still restrict specific apps via DroidShield dashboard
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(
            context, MyDeviceAdminReceiver::class.java)
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(
            android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
        for (pkg in packages) {
            try {
                dpm.enableSystemApp(adminComponent, pkg.packageName)
            } catch (e: Exception) {
                // Some packages cannot be enabled — ignore
            }
        }

        // Launch the app
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }
}