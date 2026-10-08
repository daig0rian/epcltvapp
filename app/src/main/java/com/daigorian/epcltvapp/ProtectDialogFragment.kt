package com.daigorian.epcltvapp

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * 詳細画面の「プロテクト」ボタンで開く、何を対象にするかを選ぶダイアログ。
 *
 * 選択肢ごとに対象を書いて見せる——単体なら番組名、シリーズならシリーズ名と本数。
 * 並べる選択肢は呼び出し側が [ProtectOperation.choicesFor] で決めて渡す。選ばれたものは
 * 親の [VideoDetailsFragment.onProtectOperationChosen] へ返す。
 */
class ProtectDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ProgramInfoDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_protect, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val optionsContainer = view.findViewById<ViewGroup>(R.id.protect_options)
        operations().forEach { operation ->
            val row = layoutInflater.inflate(R.layout.item_protect_option, optionsContainer, false)
            row.findViewById<TextView>(R.id.protect_option_label).setText(labelOf(operation))
            row.findViewById<TextView>(R.id.protect_option_target).text = targetOf(operation)
            row.setOnClickListener {
                (parentFragment as? VideoDetailsFragment)?.onProtectOperationChosen(operation)
                dismiss()
            }
            optionsContainer.addView(row)
        }

        view.findViewById<Button>(R.id.protect_cancel).setOnClickListener {
            dismiss()
        }
        optionsContainer.getChildAt(0)?.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * WIDTH_RATIO).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun operations(): List<ProtectOperation> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireArguments().getSerializable(ARG_OPERATIONS, ArrayList::class.java)
        } else {
            @Suppress("DEPRECATION")
            requireArguments().getSerializable(ARG_OPERATIONS) as ArrayList<*>?
        }
        return list.orEmpty().filterIsInstance<ProtectOperation>()
    }

    private fun labelOf(operation: ProtectOperation): Int = when {
        operation.isSeries && operation.protect -> R.string.protect_series
        operation.isSeries -> R.string.unprotect_series
        operation.protect -> R.string.protect_this
        else -> R.string.unprotect_this
    }

    private fun targetOf(operation: ProtectOperation): String =
        if (operation.isSeries) {
            getString(R.string.protect_target_series, operation.targetName, operation.totalCount)
        } else {
            getString(R.string.protect_target_single, operation.targetName)
        }

    companion object {
        const val TAG = "ProtectDialog"
        private const val ARG_OPERATIONS = "operations"

        /** ダイアログの幅。画面の幅に対する比。 */
        private const val WIDTH_RATIO = 0.5

        fun newInstance(operations: List<ProtectOperation>): ProtectDialogFragment {
            return ProtectDialogFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_OPERATIONS, ArrayList(operations))
                }
            }
        }
    }
}
