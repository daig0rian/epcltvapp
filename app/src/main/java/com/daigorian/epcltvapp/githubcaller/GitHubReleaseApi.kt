package com.daigorian.epcltvapp.githubcaller

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * 自分自身の最新リリースを GitHub に問い合わせるクライアント。
 *
 * **このクラスの API を呼ぶのは、利用者が「アップデートを確認」カードを押し、
 * さらに接続確認ダイアログで承諾したときだけ。** 起動時チェックや定期確認は行わない
 * (このアプリの利用者層はプライバシーへの感度が高く、意図しない外部通信を嫌うため)。
 * 接続先は録画サーバー(EPGStation)ではなく外部のインターネットである点が他の API クライアントと違う。
 *
 * `releases/latest` は draft と pre-release を GitHub 側で除外して返すため、
 * こちらでの絞り込みは不要。
 */
object GitHubReleaseApi {

    private const val BASE_URL = "https://api.github.com/"
    private const val OWNER = "daig0rian"
    private const val REPO = "epcltvapp"

    /**
     * GitHub API は User-Agent の無いリクエストを 403 で拒否する。
     * 中身は何でもよいが、識別できる値を送る。
     */
    private const val USER_AGENT = "epcltvapp-android"

    /** CI が Release に上げる APK のファイル名 (release.yml)。 */
    const val APK_ASSET_NAME = "app-release.apk"

    interface ApiInterface {
        @GET("repos/{owner}/{repo}/releases/latest")
        fun getLatestRelease(
            @Path("owner") owner: String,
            @Path("repo") repo: String
        ): Call<GitHubRelease>
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        // 相手は LAN 内の録画サーバーではなくインターネットなので、
        // EPGStation 向けクライアントより余裕を持たせる。
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/vnd.github+json")
                    .build()
            )
        }
        .build()

    private val api: ApiInterface = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()
        .create(ApiInterface::class.java)

    fun getLatestRelease(): Call<GitHubRelease> = api.getLatestRelease(OWNER, REPO)
}
