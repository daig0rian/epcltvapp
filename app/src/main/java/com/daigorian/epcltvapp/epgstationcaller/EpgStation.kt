package com.daigorian.epcltvapp.epgstationcaller

import android.R.attr
import android.util.Base64
import com.bumptech.glide.load.model.LazyHeaderFactory
import com.bumptech.glide.load.model.LazyHeaders
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Call
import retrofit2.Retrofit.Builder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.URL
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

        @DELETE("recorded/{id}")
        fun deleteRecorded(
            @Path("id") recordedId : Long
        ): Call<ApiError>

        @GET("rules/list")
        fun getRulesList(): Call<List<RuleList>>
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
                .addHeader("Authorization", BasicAuthorization(username, password))
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

    class BasicAuthorization(private val username: String, private val password: String) :
        LazyHeaderFactory {
        override fun buildHeader(): String {
            return "Basic " + Base64.encodeToString(
                "$username:$password".toByteArray(),
                Base64.NO_WRAP
            )
        }
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