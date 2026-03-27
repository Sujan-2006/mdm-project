package com.sujan.mdm

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.DELETE
import java.net.InetAddress
import java.util.concurrent.TimeUnit

interface ApiService {

    @POST("enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<ResponseBody>

    @POST("device-info")
    suspend fun sendDeviceInfo(@Body info: DeviceInfoRequest): Response<ResponseBody>

    @POST("app-inventory")
    suspend fun sendApps(@Body apps: List<AppItem>): Response<ResponseBody>

    @GET("/api/device-admin")
    suspend fun getAdminIdForDevice(@Query("deviceId") deviceId: String): Response<AdminIdResponse>

    @POST("/api/device-location")
    suspend fun sendLocation(@Body location: LocationRequest): Response<ResponseBody>

    @GET("/api/restrictions/packages")
    suspend fun getRestrictedPackages(@Query("deviceId") deviceId: String): Response<List<String>>

    @POST("/api/device/fcm-token")
    suspend fun updateFcmToken(
        @Query("deviceId") deviceId: String,
        @Query("token") token: String
    ): Response<Void>
}

object RetrofitClient {

    private const val BASE_URL =
        "https://mdm-project-5042.onrender.com/"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    val addresses = InetAddress
                        .getAllByName(hostname).toList()
                    if (addresses.isEmpty()) {
                        okhttp3.Dns.SYSTEM.lookup(hostname)
                    } else {
                        addresses
                    }
                } catch (e: Exception) {
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            }
        })
        .build()

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}