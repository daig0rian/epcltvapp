package com.daigorian.epcltvapp

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedResponse
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SearchFragment : SearchSupportFragment() , SearchSupportFragment.SearchResultProvider {
    private val mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun getResultsAdapter(): ObjectAdapter {
        return mRowsAdapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(ItemViewClickedListener())
    }


    override fun onQueryTextChange(newQuery: String?): Boolean {
        mRowsAdapter.clear()
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        mRowsAdapter.clear()
        val header = HeaderItem(0L, getString(R.string.search_results))
        val listRowAdapter = ArrayObjectAdapter(CardPresenter())
        mRowsAdapter.add(ListRow(header, listRowAdapter))

        if (!TextUtils.isEmpty(query)) {
            //V1
            EpgStation?.api?.getRecorded(keyword = query,reverse = true)?.enqueue(object :
                Callback<GetRecordedResponse> {
                override fun onResponse(
                    call: Call<GetRecordedResponse>,
                    response: Response<GetRecordedResponse>
                ) {
                    response.body()!!.recorded.forEach {
                        listRowAdapter.add(it)
                    }
                }

                override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                    Toast.makeText(
                        context!!,
                        R.string.connect_epgstation_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
            //V2
            EpgStationV2?.api?.getRecorded(keyword = query,isReverse = true)?.enqueue(object :
                Callback<Records> {
                override fun onResponse(
                    call: Call<Records>,
                    response: Response<Records>
                ) {
                    response.body()?.records?.forEach {
                        listRowAdapter.add(it)
                    }
                }

                override fun onFailure(call: Call<Records>, t: Throwable) {
                    Toast.makeText(
                        context!!,
                        R.string.connect_epgstation_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
        return true
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {

            when (item) {
                is RecordedProgram -> {
                    //V1
                    val intent = Intent(context!!, DetailsActivity::class.java)
                    intent.putExtra(DetailsActivity.RECORDEDPROGRAM, item)

                    val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity!!,
                        (itemViewHolder.view as ImageCardView).mainImageView,
                        DetailsActivity.SHARED_ELEMENT_NAME
                    )
                        .toBundle()
                    startActivity(intent, bundle)
                }
                is RecordedItem -> {
                    //V2
                    val intent = Intent(context!!, DetailsActivity::class.java)
                    intent.putExtra(DetailsActivity.RECORDEDITEM, item)

                    val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity!!,
                        (itemViewHolder.view as ImageCardView).mainImageView,
                        DetailsActivity.SHARED_ELEMENT_NAME
                    )
                        .toBundle()
                    startActivity(intent, bundle)
                }
                is String -> {
                    Toast.makeText(context!!, item, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    companion object {
        private val TAG = "SearchFragment"

    }
}