package com.daigorian.epcltvapp.epgstationv2caller

import com.bumptech.glide.load.model.LazyHeaders
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Call
import retrofit2.Retrofit.Builder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.net.URL

object EpgStationV2 {
    interface ApiInterface {
        @GET("recorded")
        fun getRecorded(
            @Query("isHalfWidth") isHalfWidth: Boolean = false,
            @Query("offset") offset: Long = 0,
            @Query("limit") limit: Long = default_limit.toLong(),
            @Query("isReverse") isReverse: Boolean = false,
            @Query("ruleId") ruleId: Long? = null,
            @Query("channelId") channelId: Long? = null,
            @Query("genre") genre: Long? = null,
            @Query("keyword") keyword: String? = null,
            @Query("hasOriginalFile") hasOriginalFile: Boolean? = null,
        ): Call<Records>

        @GET("recording")
        fun getRecording(
            @Query("offset") offset: Int = 0,
            @Query("limit") limit: Int = default_limit.toInt(),
            @Query("isHalfWidth") isHalfWidth: Boolean = false,
        ): Call<Records>

        @GET("rules")
        fun getRules(
            @Query("offset") offset: Int = 0,
            @Query("limit") limit: Int = default_limit.toInt(),
            @Query("type") type: String? = null,
            @Query("keyword") keyword: String? = null,
        ): Call<Rules>

        @GET("version")
        fun getVersion(
        ): Call<Version>
    }



    private var baseUrl:String = "http://192.168.0.0:8888/api/"
    const val default_limit:String = "24"



    private var okHttpClientBuilder = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)

    var api: ApiInterface? = null
    var authForGlide : LazyHeaders? = null

    fun initAPI(_baseUrl:String){
        baseUrl = _baseUrl
        val userInfo:String? = URL(baseUrl).userInfo
        if(userInfo != null && userInfo.matches(Regex("^[^:]+:[^:]+$")) ) {
            //Basic認証情報を含むURLである
            val username = userInfo.split(":")[0]
            val password = userInfo.split(":")[1]
            api = Builder()
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl(baseUrl)
                .client(okHttpClientBuilder.addInterceptor(BasicAuthInterceptor(username, password)).build())
                .build().create(ApiInterface::class.java)

            //サムネ読み込みなどで使われるGlideのヘッダを準備してやる
            authForGlide = LazyHeaders.Builder()
                .addHeader("Authorization", EpgStation.BasicAuthorization(username, password))
                .build()
        }else{
            //Basic認証情報を含まないURLである
            api = Builder()
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl(baseUrl)
                .client(okHttpClientBuilder.build())
                .build().create(ApiInterface::class.java)
        }
    }
    class BasicAuthInterceptor(user: String, password: String) : Interceptor {
        private val credentials: String = Credentials.basic(user, password)

        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val authenticatedRequest = request.newBuilder()
                .header("Authorization", credentials).build()
            return chain.proceed(authenticatedRequest)
        }
    }

    fun getThumbnailURL(id:String):String{
        return baseUrl + "thumbnails/" + id
    }

    fun getVideoURL(id:String):String{
        return baseUrl + "videos/" + id
    }
}