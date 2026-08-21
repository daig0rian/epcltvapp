package com.daigorian.epcltvapp

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
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
 * チャンネル一覧やストリーム設定の取得はここでは行わない。必要なものは呼び出し側が
 * 判断して取りに行く（画面によって要るものが違い、待つかどうかも違うため）。
 */
object EpgStationApiInitializer {

    private const val TAG = "EpgStationApiInitializer"

    sealed class Result {
        /** EPGStation v2 として初期化した。 */
        object V2 : Result()

        /** EPGStation v1 として初期化した。 */
        object V1 : Result()

        /** 接続できなかった。[detail] は利用者へ見せてよい補足。 */
        data class Failed(val detail: String) : Result()
    }

    /**
     * @param onResult 常に1回だけ呼ばれる。呼ばれた時点で [EpgStation] / [EpgStationV2] の
     *                 どちらか（または、どちらも失敗なら両方 null）が確定している。
     */
    fun initialize(context: Context, onResult: (Result) -> Unit) {
        EpgStation.api = null
        EpgStationV2.api = null

        val baseUrl = resolveBaseUrl(context)

        try {
            EpgStationV2VersionChecker(baseUrl).api.getVersion()
                .enqueue(object : Callback<Version> {
                    override fun onResponse(call: Call<Version>, response: Response<Version>) {
                        if (response.body() != null) {
                            Log.d(TAG, "detect Version 2.x.x")
                            EpgStationV2.initAPI(baseUrl)
                            onResult(Result.V2)
                        } else {
                            Log.d(TAG, "detect Version 1.x.x")
                            EpgStationV2.api = null
                            EpgStation.initAPI(baseUrl)
                            onResult(Result.V1)
                        }
                    }

                    override fun onFailure(call: Call<Version>, t: Throwable) {
                        Log.d(TAG, "getVersion API Failure")
                        onResult(
                            Result.Failed(context.getString(R.string.please_check_ip_and_port))
                        )
                    }
                })
        } catch (e: Exception) {
            onResult(Result.Failed(e.message.orEmpty()))
        }
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
