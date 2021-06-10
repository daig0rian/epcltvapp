package com.daigorian.epcltvapp

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import com.daigorian.epcltvapp.epgstationcaller.*
import com.daigorian.epcltvapp.epgstationv2caller.*

class DetailsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {

    override fun onBindDescription(
        viewHolder: ViewHolder,
        item: Any
    ) {
        if ( item is RecordedProgram) {

            viewHolder.title.text = item.name
            viewHolder.subtitle.text = item.description
            viewHolder.body.text = item.extended
        }else if ( item is RecordedItem) {

            viewHolder.title.text = item.name
            viewHolder.subtitle.text = item.description
            viewHolder.body.text = item.extended
        }
    }
}