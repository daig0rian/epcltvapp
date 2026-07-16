package com.daigorian.epcltvapp.epgstationv2caller

import com.bumptech.glide.load.model.LazyHeaders
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
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
            @Query("isHalfWidth") isHalfWidth: Boolean = true,
            @Query("offset") offset: Long = 0,
            @Query("limit") limit: Long = default_limit.toLong(),
            @Query("isReverse") isReverse: Boolean = false,
            @Query("ruleId") ruleId: Long? = null,
            @Query("channelId") channelId: Long? = null,
            @Query("genre") genre: Long? = null,
            @Query("keyword") keyword: String? = null,
            @Query("hasOriginalFile") hasOriginalFile: Boolean? = null,
        ): Call<Records>

        @DELETE("recorded/{recordedId}")
        fun deleteRecorded(
            @Path("recordedId") recordedId : Long
        ): Call<ApiErrorV2>

        @GET("recording")
        fun getRecording(
            @Query("offset") offset: Int = 0,
            @Query("limit") limit: Int = default_limit.toInt(),
            @Query("isHalfWidth") isHalfWidth: Boolean = true,
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

        @GET("channels")
        fun getChannels(): Call<List<ChannelItem>>

        @GET("config")
        fun getConfig(): Call<ConfigResponse>

        @GET("schedules/broadcasting")
        fun getScheduleOnAir(
            @Query("isHalfWidth") isHalfWidth: Boolean = true
        ): Call<List<Schedule>>

        @POST("reserves")
        fun addReserve(
            @Body option: ManualReserveOption
        ): Call<ResponseBody>

        @GET("streams/recorded/{videoFileId}/hls")
        fun startRecordedHlsStream(
            @Path("videoFileId") videoFileId: Long,
            @Query("ss") ss: Int = 0,
            @Query("mode") mode: Int = 0
        ): Call<HlsStream>

        @GET("streams/live/{channelId}/hls")
        fun startLiveHlsStream(
            @Path("channelId") channelId: Long,
            @Query("mode") mode: Int = 0
        ): Call<HlsStream>

        @DELETE("streams/{streamId}")
        fun stopStream(@Path("streamId") streamId: Int): Call<ApiErrorV2>

        @PUT("streams/{streamId}/keep")
        fun keepStream(@Path("streamId") streamId: Int): Call<ApiErrorV2>
    }



    private var baseUrl:String = "http://192.168.0.0:8888/api/"
    const val default_limit:String = "24"



    private var okHttpClientBuilder = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)

    var api: ApiInterface? = null
    var authForGlide : LazyHeaders? = null
    var channelMap: Map<Long, String> = emptyMap()
    var streamConfig: StreamConfig? = null

    fun fetchChannels() {
        api?.getChannels()?.enqueue(object : Callback<List<ChannelItem>> {
            override fun onResponse(call: Call<List<ChannelItem>>, response: retrofit2.Response<List<ChannelItem>>) {
                response.body()?.let { list ->
                    channelMap = list.associate { item -> item.id to item.halfWidthName.ifEmpty { item.name } }
                }
            }
            override fun onFailure(call: Call<List<ChannelItem>>, t: Throwable) {}
        })
    }

    fun fetchStreamConfig() {
        api?.getConfig()?.enqueue(object : Callback<ConfigResponse> {
            override fun onResponse(call: Call<ConfigResponse>, response: retrofit2.Response<ConfigResponse>) {
                streamConfig = response.body()?.streamConfig
            }
            override fun onFailure(call: Call<ConfigResponse>, t: Throwable) {}
        })
    }

    /**
     * ユーザーが選んだプロファイル名からmodeインデックスを解決する。
     * config.ymlの並び順が変わってもユーザーの選択がズレないよう、indexではなく名前で永続化・検索する。
     * selectedNameがnull/空文字/該当なしの場合は先頭(index 0)にフォールバックする。
     */
    fun resolveHlsProfileIndex(selectedName: String?, profileNames: List<String>): Int {
        if (!selectedName.isNullOrEmpty()) {
            val idx = profileNames.indexOf(selectedName)
            if (idx >= 0) return idx
        }
        return 0
    }

    /**
     * ライブMpegTS用。該当なしの場合はisUnconverted(無変換)のプロファイルを優先し、無ければ先頭にフォールバックする。
     */
    fun resolveM2tsProfileIndex(selectedName: String?, profiles: List<M2tsStreamParam>): Int {
        if (!selectedName.isNullOrEmpty()) {
            val idx = profiles.indexOfFirst { it.name == selectedName }
            if (idx >= 0) return idx
        }
        val unconverted = profiles.indexOfFirst { it.isUnconverted }
        return if (unconverted >= 0) unconverted else 0
    }

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

    fun getChannelLogoURL(channelId: Long): String {
        return baseUrl + "channels/" + channelId + "/logo"
    }

    fun getVideoURL(id:String):String{
        return baseUrl + "videos/" + id
    }

    /**
     * ライブmpegts直送URL。HLSと違い開始APIが不要で、このURLに接続するだけで
     * 配信が始まり、切断すると自動的に終了する（streamId管理は不要）。
     */
    fun getLiveMpegTsUrl(channelId: Long, mode: Int = 0): String {
        return baseUrl + "streams/live/$channelId/m2ts?mode=$mode"
    }

    fun getHlsStreamUrl(streamId: Int): String {
        val url = URL(baseUrl)
        val base = "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}"
        return "$base/streamfiles/stream$streamId.m3u8"
    }
}