package com.sujan.mdm

data class EnrollRequest(
    val deviceId: String,
    val enrollmentToken: String
)

data class DeviceInfoRequest(
    val deviceId: String,
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val sdkVersion: String,
    val uuid: String,
    val serial: String
)

data class AppItem(
    val deviceId: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val isSystemApp: Boolean,
    val installSource: String
)

data class EnrollResponse(
    val status: String,
    val deviceId: String
)