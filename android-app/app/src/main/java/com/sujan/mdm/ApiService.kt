package com.sujan.mdm

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.net.InetAddress
import java.util.concurrent.TimeUnit

interface ApiService {

    @POST("enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<ResponseBody>

    @POST("device-info")
    suspend fun sendDeviceInfo(@Body info: DeviceInfoRequest): Response<ResponseBody>

    @POST("app-inventory")
    suspend fun sendApps(@Body apps: List<AppItem>): Response<ResponseBody>
}

object RetrofitClient {

    private const val BASE_URL =
        "https://mdm-project-production.up.railway.app/"

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
