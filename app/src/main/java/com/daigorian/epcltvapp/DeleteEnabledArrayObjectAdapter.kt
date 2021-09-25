package com.daigorian.epcltvapp

import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import kotlin.reflect.typeOf

open class DeleteEnabledArrayObjectAdapter : ArrayObjectAdapter {
    constructor(presenterSelector: PresenterSelector?) : super(presenterSelector)
    constructor(presenter: Presenter?) : super(presenter)

    fun removeItemFromAllListRows(item:Any){
        var verticalIndex = 0
        while(verticalIndex < size()){
            val row = get(verticalIndex)
            if(row is ListRow){
                val horizontalArrayObjectAdapter = row.adapter as? ArrayObjectAdapter
                horizontalArrayObjectAdapter?.let{
                    var horizontalIndex = 0
                    while(horizontalIndex < it.size()) {
                        if(it.get(horizontalIndex).equals(item) ){
                            it.removeItems(horizontalIndex,1)
                        }
                    horizontalIndex += 1
                    }
                }

            }
            verticalIndex += 1
        }

    }

}