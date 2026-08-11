package com.daigorian.epcltvapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * 前回停止位置が記録されている動画を開いたときに出す「前回停止位置から再生しますか？」の確認。
 *
 * 再生は選択によらず先頭から始まっているため、このダイアログは
 *  - 半透明にして裏の映像を見せる([R.style.ResumePlaybackDialogTheme] で背景の減光も切っている)
 *  - 画面の右下に小さく置く(視聴の邪魔をしない)
 *  - 一度も操作されなければ [AUTO_DISMISS_SEC] 秒後に「このまま」を選んだのと同じ扱いで消える
 * という「放っておいても正しく終わる」作りにしてある。リモコンを持ち直すのが面倒な状況でも
 * 視聴を妨げないのが狙いなので、カウントダウンは残り秒数を出して予告する。
 *
 * ただし**リモコンが一度でも操作されたらカウントダウンは解除**し、以降は時間切れで消えない。
 * 操作のたびにタイマーを振り出しに戻す作りだと、選ぼうとしている最中に消える可能性が残り、
 * 「操作を始めたらもう急かされない」という一般的な自動クローズUIの振る舞いから外れるため。
 */
class ResumePlaybackDialogFragment : DialogFragment() {

    private val countdownHandler = Handler(Looper.getMainLooper())
    private var remainingSec = AUTO_DISMISS_SEC
    private var countdownView: TextView? = null
    private var dontAskAgainCheckBox: CheckBox? = null
    // 一度でも操作されたか。解除後は画面復帰(onStart)でもカウントダウンを再開しない。
    private var userInteracted = false

    private val countdownTick = object : Runnable {
        override fun run() {
            remainingSec--
            if (remainingSec <= 0) {
                // 時間切れ。「このまま」を押したのと同じ＝何もせず閉じるだけ。
                // 「次から表示しない」は反映しない——設定を変えるのは明示的なボタン操作のときだけ。
                dismissAllowingStateLoss()
                return
            }
            updateCountdown()
            countdownHandler.postDelayed(this, COUNTDOWN_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ResumePlaybackDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_resume_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        countdownView = view.findViewById(R.id.resume_countdown)
        dontAskAgainCheckBox = view.findViewById(R.id.resume_dont_ask_again)

        view.findViewById<Button>(R.id.resume_keep).setOnClickListener {
            notifyChoice(resume = false)
        }
        val resumeButton = view.findViewById<Button>(R.id.resume_from_last_position)
        resumeButton.setOnClickListener {
            notifyChoice(resume = true)
        }
        // 続きから見たい人のほうが多いという想定でレジューム側を初期フォーカスにする。
        resumeButton.requestFocus()
    }

    private fun notifyChoice(resume: Boolean) {
        (parentFragment as? PlaybackVideoFragment)
            ?.onResumeChoice(resume, dontAskAgainCheckBox?.isChecked == true)
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val margin = resources.getDimensionPixelSize(R.dimen.resume_dialog_screen_margin)
            window.attributes = window.attributes.apply {
                gravity = Gravity.BOTTOM or Gravity.END
                // BOTTOM|END 指定時、x/yはそれぞれ右端・下端からの距離になる。
                x = margin
                y = margin
            }
        }
        // Dialog.dispatchKeyEvent はビュー階層へ配る前にこのリスナーを呼ぶため、
        // フォーカスがどのボタンにあってもすべてのキー操作をここで拾える。
        // falseを返して通常のキー処理はそのまま続けさせる。
        dialog?.setOnKeyListener { _, _, _ ->
            cancelCountdown()
            false
        }
        if (!userInteracted) startCountdown()
    }

    override fun onStop() {
        super.onStop()
        countdownHandler.removeCallbacks(countdownTick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownView = null
        dontAskAgainCheckBox = null
    }

    private fun startCountdown() {
        countdownHandler.removeCallbacks(countdownTick)
        remainingSec = AUTO_DISMISS_SEC
        countdownView?.visibility = View.VISIBLE
        updateCountdown()
        countdownHandler.postDelayed(countdownTick, COUNTDOWN_INTERVAL_MS)
    }

    /**
     * 自動クローズをやめ、以降はユーザーの操作をいつまでも待つ。
     *
     * 残り秒数の表示は GONE ではなく INVISIBLE で隠す。GONE にするとダイアログの高さが
     * 縮んで、操作した瞬間にレイアウトが飛び跳ねて見えるため。
     */
    private fun cancelCountdown() {
        if (userInteracted) return
        userInteracted = true
        countdownHandler.removeCallbacks(countdownTick)
        countdownView?.visibility = View.INVISIBLE
    }

    private fun updateCountdown() {
        countdownView?.text = getString(R.string.resume_countdown, remainingSec)
    }

    companion object {
        const val TAG = "ResumePlaybackDialog"

        /** 一度も操作されなかった場合にダイアログが自動的に閉じるまでの秒数。 */
        private const val AUTO_DISMISS_SEC = 20
        private const val COUNTDOWN_INTERVAL_MS = 1_000L
    }
}
