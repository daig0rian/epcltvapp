package com.daigorian.epcltvapp.epgstationv2caller

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit.Builder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

class EpgStationV2VersionChecker(
    baseUrl:String
) {
    interface ApiInterface {
         @GET("version")
        fun getVersion(
        ): Call<Version>
    }

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    val api: ApiInterface =  Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .build().create(ApiInterface::class.java)

}