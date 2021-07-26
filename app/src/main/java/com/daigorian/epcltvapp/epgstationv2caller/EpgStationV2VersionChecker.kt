package com.daigorian.epcltvapp.epgstationv2caller

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Call
import retrofit2.Retrofit.Builder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

class EpgStationV2VersionChecker(
    baseUrl:String
) {
    interface ApiInterface {
         @GET("version")
        fun getVersion(
        ): Call<Version>
    }

    private var okHttpClientBuilder = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)


    private val userinfo:String? = URL(baseUrl).userInfo
    val api: ApiInterface =  if(userinfo!= null && userinfo.matches(Regex("^[^:]+:[^:]+$"))) {
        Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl(baseUrl)
            .client(okHttpClientBuilder.addInterceptor(
                EpgStationV2.BasicAuthInterceptor(
                    userinfo.split(":")[0],
                    userinfo.split(":")[1]
                )
            ).build())
            .build().create(ApiInterface::class.java)
    }else {
        Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl(baseUrl)
            .client(okHttpClientBuilder.build())
            .build().create(ApiInterface::class.java)
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

}