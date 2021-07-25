package com.daigorian.epcltvapp.epgstationcaller

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit.Builder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

object EpgStation {
    interface ApiInterface {
        @GET("recorded")
        fun getRecorded(
            @Query("limit") limit: Long = default_limit.toLong(),
            @Query("offset") offset: Long = 0,
            @Query("reverse") reverse: Boolean = false,
            @Query("rule") rule: Long? = null,
            @Query("genre1") genre1: Long? = null,
            @Query("channel") channel: Long? = null,
            @Query("keyword") keyword: String? = null,
            @Query("hasTs") hasTs: Boolean? = null,
            @Query("recording") recording: Boolean? = null
        ): Call<GetRecordedResponse>

        @GET("rules/list")
        fun getRulesList(): Call<List<RuleList>>
    }

    private var baseUrl:String = "http://192.168.0.0:8888/api/"
    var default_limit:String = "24"

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    var api: ApiInterface? = null

    fun initAPI(_baseUrl:String){
        baseUrl = _baseUrl
        api = Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .build().create(ApiInterface::class.java)
    }


    fun getThumbnailURL(id:String):String{
        return baseUrl + "recorded/" + id + "/thumbnail"
    }
    fun getTsVideoURL(id:String):String{
        return baseUrl + "recorded/" + id + "/file"
    }
    fun getEncodedVideoURL(id:String,encid:String):String{
        return baseUrl + "recorded/" + id +"/file?encodedId=" + encid
    }

}