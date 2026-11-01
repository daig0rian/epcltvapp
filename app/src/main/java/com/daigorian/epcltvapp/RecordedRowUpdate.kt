package com.daigorian.epcltvapp

import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DiffCallback
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem

/** Replace a fetched prefix in server order while retaining the identities of surviving cards. */
internal fun updateRecordedRow(adapter: ArrayObjectAdapter, records: List<Any>) {
    adapter.setItems(records, RecordedRowDiff)
}

internal object RecordedRowDiff : DiffCallback<Any>() {
    override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean = when {
        oldItem is RecordedProgram && newItem is RecordedProgram -> oldItem.id == newItem.id
        oldItem is RecordedItem && newItem is RecordedItem -> oldItem.id == newItem.id
        else -> oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: Any, newItem: Any) = oldItem == newItem
}
