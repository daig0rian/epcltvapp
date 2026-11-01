package com.daigorian.epcltvapp

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedRowUpdateTest {
    private fun recording(id: Long) = RecordedProgram(id = id, hasThumbnail = false)
    // Exercise the same DiffUtil callbacks as ArrayObjectAdapter without Android's Observable stub.
    private fun notifications(old: List<Any>, new: List<Any>): List<String> {
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                RecordedRowDiff.areItemsTheSame(old[oldPosition], new[newPosition])
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                RecordedRowDiff.areContentsTheSame(old[oldPosition], new[newPosition])
        })
        val changes = mutableListOf<String>()
        result.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { changes.add("insert:$position:$count") }
            override fun onRemoved(position: Int, count: Int) { changes.add("remove:$position:$count") }
            override fun onMoved(fromPosition: Int, toPosition: Int) { changes.add("move:$fromPosition:$toPosition") }
            override fun onChanged(position: Int, count: Int, payload: Any?) { changes.add("change:$position:$count") }
        })
        return changes
    }

    @Test fun refreshPrependsNewRecordingsAndRemovesOldFooter() {
        val old = listOf(recording(2), recording(1), "continuation")
        val response = listOf(recording(3), recording(2))
        assertEquals(listOf("remove:1:2", "insert:0:1"), notifications(old, response))
    }

    @Test fun refreshUpdatesMetadataWithoutRemovingTheSameRecording() {
        val original = recording(1)
        assertEquals(
            listOf("change:0:1"),
            notifications(listOf(original), listOf(original.copy(protection = true)))
        )
    }

    @Test fun initialPageUsesOneInsertionNotification() {
        assertEquals(listOf("insert:0:12"), notifications(emptyList(), (1L..12L).map(::recording)))
    }

    @Test fun emptyResponseRemovesCardsAndContinuationTogether() {
        assertEquals(
            listOf("remove:0:2"),
            notifications(listOf(recording(1), "continuation"), emptyList())
        )
    }
}
