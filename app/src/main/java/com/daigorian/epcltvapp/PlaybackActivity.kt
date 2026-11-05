package com.daigorian.epcltvapp

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity

/** Loads [PlaybackVideoFragment]. */
class PlaybackActivity : FragmentActivity() {

    /**
     * ライブ視聴中に左右キーで局を変えるための局一覧([PlaybackVideoFragment.onChannelKey])。
     * 局を変えるとフラグメントは作り直されるが、こちらは持ち越して取り直さずに済ませる。
     */
    val liveChannelLineup = LiveChannelLineup()

    /**
     * 左右キーで局を変えたときに画面の端から出す矢印。フラグメントは局を変えるたびに作り直されて
     * 矢印ごと消えてしまうので、こちらが持つ。
     */
    private var leftArrow: EdgeArrowView? = null
    private var rightArrow: EdgeArrowView? = null

    /** 局を変えたときに出す局名のトースト。続けて変えたら前のものを消してから出す。 */
    private var channelToast: Toast? = null

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
            // 前の再生で局を変えたまま、ホームが受け取らずに残った控えを持ち越さない
            lastSwitchedLiveChannelId = null
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PlaybackVideoFragment())
                .commit()
        }
        onBackPressedDispatcher.addCallback(this, backToDetailsCallback)
    }

    /**
     * リモコンの専用キー(字幕・音声切換・早戻し/早送り・前/次・停止)と、ライブ視聴中の左右キーを、
     * ビュー階層へ配る前に再生中のフラグメントへ渡す。ここで受ける理由は
     * [PlaybackVideoFragment.onRemoteKey] と [PlaybackVideoFragment.onChannelKey] 参照。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val playback = supportFragmentManager.findFragmentById(android.R.id.content) as? PlaybackVideoFragment
        if (playback?.onRemoteKey(event) == true) return true
        if (playback?.onChannelKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /** 押した側の画面の端から矢印を出す。 */
    fun showChannelSwitchArrow(forward: Boolean) {
        val arrow = (if (forward) rightArrow else leftArrow) ?: createChannelSwitchArrow(forward)
        arrow.play()
    }

    /**
     * 矢印を、フラグメントと同じ入れ物(android.R.id.content)に重ねる。フラグメントの画面は後から足されて
     * 上に重なるので、Z を上げて手前に描かせる。
     */
    private fun createChannelSwitchArrow(forward: Boolean): EdgeArrowView {
        val margin = (EdgeArrowView.EDGE_MARGIN_DP * resources.displayMetrics.density).toInt()
        val arrow = EdgeArrowView(this, pointsRight = forward).apply {
            translationZ = ARROW_TRANSLATION_Z
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or if (forward) Gravity.END else Gravity.START
            ).apply {
                marginStart = margin
                marginEnd = margin
            }
        }
        addContentView(arrow, arrow.layoutParams)
        if (forward) rightArrow = arrow else leftArrow = arrow
        return arrow
    }

    /** 局を変えたことを知らせる。続けて変えたら前のものを消してから出す(溜めると押し終わった後も出続けるため)。 */
    fun showChannelToast(message: String) {
        channelToast?.cancel()
        channelToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    /**
     * シリーズの別の回へ切り替える。戻るボタンは今見ていた回の詳細画面へ戻すようにする([leavePlayback])。
     */
    fun switchProgram(extras: Bundle) {
        if (!replacePlayback(extras)) return
        switchedProgramExtras = extras
        backToDetailsCallback.isEnabled = true
    }

    /**
     * ライブ視聴の局を変える。戻るボタンは何も挟まずホームへ戻るまま。
     * ホームはライブ視聴の行のカーソルをこの局へ合わせる([takeLastSwitchedLiveChannelId])。
     */
    fun switchLiveChannel(extras: Bundle, channelId: Long) {
        if (!replacePlayback(extras)) return
        lastSwitchedLiveChannelId = channelId
    }

    /**
     * 再生中のフラグメントを、[extras] で組み立て直したものに差し替える。
     *
     * **今のフラグメントを同期的に破棄してから**次を追加する。ExoPlayer の解放(onDestroyView)を
     * 次のプレーヤーの生成より確実に先に済ませるため——映像デコーダーのインスタンスが一瞬でも
     * 2つ開くと、機種によってはデコードがハングして端末の再起動でしか復旧しなくなる
     * (CLAUDE.md「TSシークバーのサムネイル表示」参照)。
     *
     * @return 差し替えたか。画面を閉じる途中なら false。
     */
    private fun replacePlayback(extras: Bundle): Boolean {
        if (isFinishing || isDestroyed) return false
        // 画面が保存状態に入った直後(再生終了と同時にホームへ抜けた場合など)に例外で落ちない
        // よう AllowingStateLoss を使う。失われて困る状態は無い——次のフラグメントは
        // intent から組み立て直されるため。
        supportFragmentManager.findFragmentById(android.R.id.content)?.let { current ->
            supportFragmentManager.beginTransaction().remove(current).commitNowAllowingStateLoss()
        }
        // 新しいフラグメントは activity の intent を読んで自分を組み立てるので、先に差し替える。
        intent.replaceExtras(extras)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, PlaybackVideoFragment())
            .commitAllowingStateLoss()
        return true
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

    companion object {
        /** 矢印を再生画面より手前に描かせるための Z。Leanback の再生画面は Z を持たないので、正の値なら足りる。 */
        private const val ARROW_TRANSLATION_Z = 100f

        /**
         * ライブ視聴の左右キーで最後に移った局。局を変えなかった再生では null のまま。
         *
         * ホームから再生画面を開く経路は、カードの決定(MainFragment)と長押しメニューの HLS
         * (OriginalCardPresenter)に分かれていて、どちらからも結果を受け取れる形になっていない。
         * そのためここに控え、ホームが onResume で受け取る。
         */
        private var lastSwitchedLiveChannelId: Long? = null

        /** [lastSwitchedLiveChannelId] を受け取って消す。 */
        fun takeLastSwitchedLiveChannelId(): Long? =
            lastSwitchedLiveChannelId.also { lastSwitchedLiveChannelId = null }
    }
}
