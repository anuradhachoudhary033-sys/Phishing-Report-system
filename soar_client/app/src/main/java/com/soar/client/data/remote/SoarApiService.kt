package com.soar.client.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface SoarApiService {

    @GET("api/v1/inbox")
    suspend fun getInbox(
        @Query("email") email: String,
        @Query("max_results") maxResults: Int = 100,
        @Query("page_token") pageToken: String? = null,
        @Query("label") label: String = "INBOX"
    ): InboxResponse

    @GET("api/v1/inbox/{msg_id}")
    suspend fun getEmailDetail(
        @Path("msg_id") msgId: String,
        @Query("email") email: String
    ): EmailDetailDto

    @POST("api/v1/inbox/{msg_id}/trash")
    suspend fun trashEmail(
        @Path("msg_id") msgId: String,
        @Query("email") email: String
    ): TrashResponse

    @POST("api/v1/send")
    suspend fun sendEmail(
        @Body payload: SendEmailPayload
    ): SendEmailResponse

    companion object {
        // Default local backend emulator URL; can be dynamically overridden in app settings
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"

        fun create(baseUrl: String = DEFAULT_BASE_URL): SoarApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            return Retrofit.Builder()
                .baseUrl(sanitizedUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SoarApiService::class.java)
        }
    }
}
