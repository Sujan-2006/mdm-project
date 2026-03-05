package com.sujan.mdm

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<ResponseBody>

    @POST("device-info")
    suspend fun sendDeviceInfo(@Body info: DeviceInfoRequest): Response<ResponseBody>

    @POST("app-inventory")
    suspend fun sendApps(@Body apps: List<AppItem>): Response<ResponseBody>
}

object RetrofitClient {

    private const val BASE_URL = "https://mdm-project-production.up.railway.app/"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
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