package com.daigorian.epcltvapp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity

/** Loads [PlaybackVideoFragment]. */
class PlaybackActivity : FragmentActivity() {

    /**
     * シリーズ連続再生で切り替えた先の番組。起動時のまま(切り替えていない)なら null。
     * 再生画面を離れるときにどの詳細画面へ戻すかの判断に使う([leavePlayback])。
     */
    private var switchedProgramExtras: Bundle? = null

    /**
     * 戻るボタンを [leavePlayback] へ回すためのもの。
     *
     * **番組を切り替えたときだけ有効にする。** 切り替えていなければ戻るボタンは
     * 何も挟まず既定の動作(この画面を閉じる)のままにしておきたいため。
     */
    private val backToDetailsCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            leavePlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 再生中に無操作でスクリーンセーバーへ入るのを防ぐ
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PlaybackVideoFragment())
                .commit()
        }
        onBackPressedDispatcher.addCallback(this, backToDetailsCallback)
    }

    /**
     * シリーズの別の回へ切り替える。
     *
     * **今のフラグメントを同期的に破棄してから**次を追加する。ExoPlayer の解放(onDestroyView)を
     * 次のプレーヤーの生成より確実に先に済ませるため——映像デコーダーのインスタンスが一瞬でも
     * 2つ開くと、機種によってはデコードがハングして端末の再起動でしか復旧しなくなる
     * (CLAUDE.md「TSシークバーのサムネイル表示」参照)。
     */
    fun switchProgram(extras: Bundle) {
        if (isFinishing || isDestroyed) return
        // 画面が保存状態に入った直後(再生終了と同時にホームへ抜けた場合など)に例外で落ちない
        // よう AllowingStateLoss を使う。失われて困る状態は無い——次のフラグメントは
        // intent から組み立て直されるため。
        supportFragmentManager.findFragmentById(android.R.id.content)?.let { current ->
            supportFragmentManager.beginTransaction().remove(current).commitNowAllowingStateLoss()
        }
        // 新しいフラグメントは activity の intent を読んで自分を組み立てるので、先に差し替える。
        intent.replaceExtras(extras)
        switchedProgramExtras = extras
        backToDetailsCallback.isEnabled = true
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, PlaybackVideoFragment())
            .commitAllowingStateLoss()
    }

    /**
     * 再生画面を離れる。
     *
     * 連続再生で番組が変わっている場合は、スタックに残っている「最初に開いた番組」の詳細画面
     * ではなく、**今見ていた番組**の詳細画面へ差し替えて戻す。3話まで自動で見たのに戻ると
     * 1話の詳細が出る、という食い違いを避けるため。
     */
    fun leavePlayback() {
        val extras = switchedProgramExtras
        if (extras == null) {
            finish()
            return
        }
        // FLAG_ACTIVITY_CLEAR_TOP でスタック上の詳細画面より上(=この再生画面)を畳み、
        // 詳細画面自体は新しい番組の intent で作り直させる。詳細画面が読むのは番組の
        // オブジェクトだけなので、再生用の指定(再生するファイル等)が混ざっていても害はない。
        val details = Intent(this, DetailsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtras(extras)
        startActivity(details)
        finish()
    }
}
