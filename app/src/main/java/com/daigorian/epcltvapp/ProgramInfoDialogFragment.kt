package com.daigorian.epcltvapp

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment

class ProgramInfoDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ProgramInfoDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_program_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.program_info_title).text = requireArguments().getString(ARG_TITLE)

        val bodyTextView = view.findViewById<TextView>(R.id.program_info_body)
        bodyTextView.text = requireArguments().getString(ARG_BODY)

        val scrollView = view.findViewById<ScrollView>(R.id.program_info_scroll)
        scrollView.requestFocus()
        scrollView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val scrollAmount = bodyTextView.lineHeight * 3
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (scrollView.canScrollVertically(1)) {
                            scrollView.smoothScrollBy(0, scrollAmount)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (scrollView.canScrollVertically(-1)) {
                            scrollView.smoothScrollBy(0, -scrollAmount)
                            true
                        } else false
                    }
                    else -> false
                }
            } else false
        }

        view.findViewById<Button>(R.id.program_info_close).setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.7).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.8).toInt()
        dialog?.window?.setLayout(width, height)
    }

    companion object {
        const val TAG = "ProgramInfoDialog"
        private const val ARG_TITLE = "title"
        private const val ARG_BODY = "body"

        fun newInstance(title: String, body: String): ProgramInfoDialogFragment {
            return ProgramInfoDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_BODY, body)
                }
            }
        }
    }
}
