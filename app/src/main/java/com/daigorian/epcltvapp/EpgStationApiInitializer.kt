package com.daigorian.epcltvapp

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2VersionChecker
import com.daigorian.epcltvapp.epgstationv2caller.Version
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Preference に保存された接続先から EPGStation の API クライアントを用意する。
 *
 * 元は MainFragment の中にあったが、deep link の受け口（[DeepLinkActivity]）からも同じ
 * 初期化が要るため切り出した。**画面を持たない**——Toast も画面遷移もここでは行わず、
 * 結果だけ呼び出し側へ返す。
 *
 * つないでよい相手かの判定は [ServerCompatibility] が持つ。
 *
 * チャンネル一覧やストリーム設定の取得はここでは行わない。必要なものは呼び出し側が
 * 判断して取りに行く（画面によって要るものが違い、待つかどうかも違うため）。
 */
object EpgStationApiInitializer {

    private const val TAG = "EpgStationApiInitializer"

    sealed class Result {
        /** 接続した。 */
        object Connected : Result()

        /** 接続先が EPGStation v1 だった。サポートを終えたので接続しない。 */
        object UnsupportedV1 : Result()

        /** 接続できなかった。[detail] は利用者へ見せてよい補足。 */
        data class Failed(val detail: String) : Result()
    }

    /**
     * @param onResult 常に1回だけ呼ばれる。呼ばれた時点で [EpgStationV2] の状態が確定している
     *                 （接続したときだけ api が非 null）。
     */
    fun initialize(context: Context, onResult: (Result) -> Unit) {
        EpgStationV2.disconnect()

        val baseUrl = resolveBaseUrl(context)
        val connectFailed = { Result.Failed(context.getString(R.string.please_check_ip_and_port)) }

        try {
            val checker = EpgStationV2VersionChecker(baseUrl)
            checker.api.getVersion()
                .enqueue(object : Callback<Version> {
                    override fun onResponse(call: Call<Version>, response: Response<Version>) {
                        val body = response.body()
                        val next = ServerCompatibility.afterVersion(
                            response.code(), body != null, body?.version
                        )
                        when (next) {
                            is ServerCompatibility.AfterVersion.Connect -> {
                                Log.d(TAG, "connect: version=${next.version}")
                                EpgStationV2.initAPI(baseUrl, next.version)
                                onResult(Result.Connected)
                            }

                            ServerCompatibility.AfterVersion.CheckV1 -> checkV1(checker, onResult, connectFailed)

                            ServerCompatibility.AfterVersion.Fail -> {
                                Log.d(TAG, "getVersion: HTTP${response.code()} version=${body?.version}")
                                onResult(connectFailed())
                            }
                        }
                    }

                    override fun onFailure(call: Call<Version>, t: Throwable) {
                        Log.d(TAG, "getVersion API Failure")
                        onResult(connectFailed())
                    }
                })
        } catch (e: Exception) {
            onResult(Result.Failed(e.message.orEmpty()))
        }
    }

    /** `/api/version` が 404 だったとき。v1 なら、つながらない理由をそう伝える。 */
    private fun checkV1(
        checker: EpgStationV2VersionChecker,
        onResult: (Result) -> Unit,
        connectFailed: () -> Result,
    ) {
        checker.api.getV1Storage().enqueue(object : Callback<Unit> {
            override fun onResponse(call: Call<Unit>, response: Response<Unit>) {
                if (ServerCompatibility.isV1(response.code())) {
                    Log.d(TAG, "detect EPGStation v1 (unsupported)")
                    onResult(Result.UnsupportedV1)
                } else {
                    Log.d(TAG, "getVersion: 404, storage: HTTP${response.code()}")
                    onResult(connectFailed())
                }
            }

            override fun onFailure(call: Call<Unit>, t: Throwable) {
                Log.d(TAG, "getV1Storage API Failure")
                onResult(connectFailed())
            }
        })
    }

    /** Preference の「カスタムURL」設定に従って base URL を組み立てる。 */
    private fun resolveBaseUrl(context: Context): String {
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
        val useCustomBaseURL =
            pref.getBoolean(context.getString(R.string.pref_key_use_custom_base_url), false)

        return if (useCustomBaseURL) {
            pref.getString(
                context.getString(R.string.pref_key_custom_base_url),
                context.getString(R.string.pref_val_custom_base_url_default)
            )!!
        } else {
            val ipAddress = pref.getString(
                context.getString(R.string.pref_key_ip_addr),
                context.getString(R.string.pref_val_ip_addr_default)
            )!!
            val port = pref.getString(
                context.getString(R.string.pref_key_port_num),
                context.getString(R.string.pref_val_port_num_default)
            )!!
            "http://$ipAddress:$port/api/"
        }
    }
}
