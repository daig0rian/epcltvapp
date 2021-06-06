package com.daigorian.epcltvapp

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class SearchActivity: FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.search_fragment, SearchFragment())
                .commitNow()
        }
    }
}