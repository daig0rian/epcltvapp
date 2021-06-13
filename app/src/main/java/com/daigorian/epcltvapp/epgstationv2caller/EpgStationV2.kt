package com.daigorian.epcltvapp.epgstationv2caller

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit.Builder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

object EpgStationV2 {
    interface ApiInterface {
        @GET("recorded")
        fun getRecorded(
            @Query("isHalfWidth") isHalfWidth: Boolean = false,
            @Query("offset") offset: Int = 0,
            @Query("limit") limit: Int = default_limit.toInt(),
            @Query("isReverse") isReverse: Boolean = false,
            @Query("ruleId") ruleId: Long? = null,
            @Query("channelId") channelId: Int? = null,
            @Query("genre") genre: Int? = null,
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
    }

    private var ip:String = "192.168.0.0"
    private var port:String = "8888"
    var default_limit:String = "24"

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    var api: ApiInterface? = null

    fun initAPI(_ip:String, _port:String){
        ip = _ip
        port = _port
        api = Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl(getBaseURL())
            .client(okHttpClient)
            .build().create(ApiInterface::class.java)
    }

    private fun getBaseURL():String{
        return "http://$ip:$port/api/"
    }
    fun getThumbnailURL(id:String):String{
        return getBaseURL() + "thumbnails/" + id
    }
    fun getVideoURL(id:String):String{
        return getBaseURL() + "videos/" + id
    }
}