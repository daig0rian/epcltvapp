package com.daigorian.epcltvapp

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
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

        //もしAmazon Fire TV端末だった場合インアプリ音声検索は使えないのでコールバックをオーバーライドする
        if (requireContext().packageManager.hasSystemFeature(AMAZON_FEATURE_FIRE_TV)) {
            setSpeechRecognitionCallback {
                //Do nothing
            }
        }
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
            // EPGStation Version 1.x.x
            EpgStation.api?.getRecorded(keyword = query,reverse = true)?.enqueue(object :
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
            // EPGStation Version 2.x.x
            EpgStationV2.api?.getRecorded(keyword = query,isReverse = true)?.enqueue(object :
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
                    // EPGStation Version 1.x.x
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
                    /// EPGStation Version 2.x.x
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
        private const val TAG = "SearchFragment"
        private const val AMAZON_FEATURE_FIRE_TV = "amazon.hardware.fire_tv"

    }
}