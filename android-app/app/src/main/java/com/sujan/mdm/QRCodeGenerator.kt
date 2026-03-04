package com.sujan.mdm

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QRCodeGenerator {

    fun generateQRCode(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }
        return bitmap
    }

    fun generateEnrollmentQRContent(
        packageName: String,
        deviceAdminReceiver: String,
        enrollmentToken: String,
        wifiSsid: String = "",
        wifiPassword: String = ""
    ): String {
        return """
            {
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "$packageName/$deviceAdminReceiver",
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "http://10.0.2.2:8080/apk/mdm.apk",
                "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
                "android.app.extra.PROVISIONING_WIFI_SSID": "$wifiSsid",
                "android.app.extra.PROVISIONING_WIFI_PASSWORD": "$wifiPassword",
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
                    "enrollment_token": "$enrollmentToken"
                }
            }
        """.trimIndent()
    }
}