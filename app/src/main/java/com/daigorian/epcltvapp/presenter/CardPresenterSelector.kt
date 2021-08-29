package com.daigorian.epcltvapp.presenter

import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector

class CardPresenterSelector() : PresenterSelector() {
    private val originalCardPresenter = OriginalCardPresenter()

    override fun getPresenter(item: Any): Presenter {

        return originalCardPresenter
    }
}
