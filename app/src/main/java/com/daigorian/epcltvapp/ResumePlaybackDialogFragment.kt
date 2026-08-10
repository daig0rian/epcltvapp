package com.daigorian.epcltvapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment

/**
 * 前回停止位置が記録されている動画を開いたときに出す「レジューム再生しますか？」の確認。
 *
 * 再生は選択によらず最初から始まっているため、このダイアログは半透明にして裏の映像を
 * 見せる([R.style.ResumePlaybackDialogTheme] で背景の減光も切っている)。
 * 「最初から」はダイアログを閉じるだけで、何もしなければそのまま先頭から見続けられる。
 */
class ResumePlaybackDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ResumePlaybackDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_resume_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.resume_from_beginning).setOnClickListener {
            notifyChoice(resume = false)
        }
        val resumeButton = view.findViewById<Button>(R.id.resume_playback)
        resumeButton.setOnClickListener {
            notifyChoice(resume = true)
        }
        // 続きから見たい人のほうが多いという想定でレジューム側を初期フォーカスにする。
        resumeButton.requestFocus()
    }

    private fun notifyChoice(resume: Boolean) {
        (parentFragment as? PlaybackVideoFragment)?.onResumeChoice(resume)
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        const val TAG = "ResumePlaybackDialog"
    }
}
