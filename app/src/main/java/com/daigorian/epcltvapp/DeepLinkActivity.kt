package com.daigorian.epcltvapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2

/**
 * 外部から投げられた deep link を受けて再生を始めるためだけの、画面を持たない入り口。
 *
 *     adb shell am start -a android.intent.action.VIEW -d "epcltvapp://live/channelId/3239123608"
 *
 * URI の解釈は [LiveDeepLink]、接続先の初期化は [EpgStationApiInitializer] が持つ。ここは
 * その2つを繋いで [PlaybackActivity] へ渡すだけにしてある。
 *
 * **解釈できない URI や存在しないチャンネルは、黙って既定へ倒さず Toast で知らせる。**
 * 自動化から投げられる経路なので、誤りが silent に潰れると利用者が気づけないため。
 */
class DeepLinkActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val target = uri?.let { LiveDeepLink.parse(it.scheme, it.host, it.pathSegments) }
        if (target == null) {
            Log.d(TAG, "unsupported deep link: $uri")
            failWith(getString(R.string.deep_link_unsupported))
            return
        }

        EpgStationApiInitializer.initialize(this) { result ->
            when (result) {
                is EpgStationApiInitializer.Result.V2 -> fetchThenStart(target)

                is EpgStationApiInitializer.Result.V1 ->
                    failWith(getString(R.string.deep_link_requires_v2))

                is EpgStationApiInitializer.Result.Failed ->
                    failWith(getString(R.string.connect_epgstation_failed) + "\n" + result.detail)
            }
        }
    }

    /**
     * 再生に必要なものが揃うのを待つ。
     *
     * - ストリーム設定 … 未取得だとプロファイル選択が既定へ落ち、利用者の設定が無視される
     * - チャンネル一覧 … 表示名の解決と、指定されたチャンネルが実在するかの確認に使う
     *
     * Retrofit のコールバックはメインスレッドで呼ばれるため、この件数の数え方で足りる。
     */
    private fun fetchThenStart(target: LiveDeepLink.Target) {
        var remaining = 2
        val countDown = {
            remaining -= 1
            if (remaining == 0) start(target)
        }
        EpgStationV2.fetchStreamConfig(countDown)
        EpgStationV2.fetchChannels(countDown)
    }

    private fun start(target: LiveDeepLink.Target) {
        if (isFinishing || isDestroyed) return

        val channelId = when (target) {
            is LiveDeepLink.Target.ByChannelId -> target.channelId
        }

        // 一覧そのものが空なら、チャンネルが無いのではなく取得に失敗している
        if (EpgStationV2.channelMap.isEmpty()) {
            failWith(getString(R.string.connect_epgstation_failed))
            return
        }

        val channelName = EpgStationV2.channelMap[channelId]
        if (channelName == null) {
            Log.d(TAG, "channel not found: $channelId")
            failWith(getString(R.string.deep_link_channel_not_found, channelId.toString()))
            return
        }

        startActivity(
            Intent(this, PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.IS_LIVE_MPEGTS, true)
                putExtra(DetailsActivity.CHANNEL_ID, channelId)
                putExtra(DetailsActivity.CHANNEL_NAME, channelName)
            }
        )
        finish()
    }

    private fun failWith(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val TAG = "DeepLinkActivity"
    }
}
