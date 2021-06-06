package com.daigorian.epcltvapp

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter

class DetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {

    override fun onBindDescription(
        viewHolder: ViewHolder,
        item: Any
    ) {
        val recordedProgram = item as RecordedProgram

        viewHolder.title.text = recordedProgram.name
        viewHolder.subtitle.text = recordedProgram.description
        viewHolder.body.text = recordedProgram.extended
    }
}