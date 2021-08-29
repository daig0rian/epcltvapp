package com.daigorian.epcltvapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log

import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.preference.PreferenceManager
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedParam
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedResponse
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.GetRecordedParamV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import com.daigorian.epcltvapp.presenter.CardPresenterSelector
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SearchFragment : SearchSupportFragment() , SearchSupportFragment.SearchResultProvider {
    private val mListRowPresenter = ListRowPresenter()
    private val mRowsAdapter = CustomArrayObjectAdapter(mListRowPresenter)

    override fun getResultsAdapter(): ObjectAdapter {
        return mRowsAdapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(ItemViewClickedListener())
        setOnItemViewSelectedListener(ItemViewSelectedListener())
        //プレースホルダーに入る文字列
        // XXXを音声検索　XXXを検索　というように状況に応じて後ろに文言がつく
        super.setTitle(getString(R.string.program_name))

        //もしAmazon Fire TV端末だった場合インアプリ音声検索は使えないのでコールバックをオーバーライドする
        if (requireContext().packageManager.hasSystemFeature(AMAZON_FEATURE_FIRE_TV)) {
            setSpeechRecognitionCallback {
                Log.i(TAG, "SpeechRecognitionCallback")
            }
            //　TODO Amazon Fire TV端末だったら"音声検索" プレースホルダーも混乱を招くので消す
            //　TODO Amazon Fire TV端末だったらマイクオーブも混乱を招くので消す


        }
        mRowsAdapter.clear()
        getHistory(requireContext()).forEach { queryHistory ->
            addResultRow(query = queryHistory, recodeHistory=false,showErrorToast = false)
        }

    }

    override fun onQueryTextChange(newQuery: String?): Boolean {
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {


        if (!TextUtils.isEmpty(query)) {
            addResultRow(query)
        }
        return true
    }

    private inner class CustomArrayObjectAdapter(presenter: Presenter?) :
        ArrayObjectAdapter(presenter) {

        //単純に ArrayObjectAdapter.add(0,item) とするとフォーカスがArrayObjectAdapter[1]に残り続けるので
        //ArrayObjectAdapter.replace(0,item)とすることでフォーカスを移さないようにした工夫。
        fun addToTop(item : Any){
            if(size() > 0) {
                add(this[size()-1])
                for(i in size() -2 downTo  0 ){
                    replace(i+1,this[i])
                }
                replace(0,item)

            }else{
                add(item)
            }
        }
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



    private fun addResultRow(query:String, recodeHistory:Boolean = true, showErrorToast:Boolean=true){

        //検索文字列が空文字の場合は何もしない
        if(query.isEmpty()) return

        //まずは結果行を加える。（API呼出し後の処理の中の非同期処理で加えると連続処理したときに場所が不確定になってしまうため）
        val newResultRowHeader = HeaderItem(query)
        val newResultRowContents = ArrayObjectAdapter(CardPresenterSelector())
        val newResultRow = ListRow(newResultRowHeader, newResultRowContents)
        mRowsAdapter.addToTop(newResultRow)

        //このあと行にアイテムを加えていく。
        //検索結果が０件だったらその行は消す。

        // EPGStation Version 1.x.x
        EpgStation.api?.getRecorded(keyword = query,reverse = true)?.enqueue(object :
            Callback<GetRecordedResponse> {
            override fun onResponse(
                call: Call<GetRecordedResponse>,
                response: Response<GetRecordedResponse>
            ) {
                // レスポンスがあった
                response.body()?.let{ getRecordedResponse ->
                    if(getRecordedResponse.total > 0){
                        // 検索結果にヒットする録画があった
                        getRecordedResponse.recorded.forEach {
                            newResultRowContents.add(it)
                        }
                        //続きがあるなら"次を読み込む"を置く。
                        val numOfItem = getRecordedResponse.recorded.count().toLong()
                        if (numOfItem < getRecordedResponse.total) {
                            newResultRowContents.add(GetRecordedParam(keyword = query,reverse = true,offset =numOfItem))
                        }
                        // 検索履歴にも加える
                        if(recodeHistory) addHistory(query)

                    }else{
                        // 検索結果にヒットする録画がなかった
                        // 結果行は消す
                        mRowsAdapter.remove(newResultRow)
                        // 見つからなかったよメッセージを出す。
                        if(showErrorToast) {
                            Toast.makeText(
                                context!!,
                                getString(R.string.programs_not_found, query),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                }
            }
            override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                // 通信エラーなどで応答がなかったら接続できませんでしたメッセージを出す
                if(showErrorToast) {
                    Toast.makeText(
                        context!!,
                        R.string.connect_epgstation_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        // EPGStation Version 2.x.x
        EpgStationV2.api?.getRecorded(keyword = query,isReverse = true)?.enqueue(object :
            Callback<Records> {
            override fun onResponse(
                call: Call<Records>,
                response: Response<Records>
            ) {
                response.body()?.let { records ->
                    if(records.total > 0){
                        // 検索結果にヒットする録画があった
                        records.records.forEach {
                            newResultRowContents.add(it)
                        }
                        //続きがあるなら"次を読み込む"を置く。
                        val numOfItem = records.records.count().toLong()
                        if (numOfItem < records.total) {
                            newResultRowContents.add(GetRecordedParamV2(keyword = query,isReverse = true,offset =numOfItem))
                        }

                        // 検索履歴にも加える
                        if(recodeHistory) addHistory(query)

                    }else{
                        // 検索結果にヒットする録画がなかった
                        // 結果行は消す
                        mRowsAdapter.remove(newResultRow)
                        // 見つからなかったよメッセージを出す。
                        if(showErrorToast) {
                            Toast.makeText(
                                context!!,
                                getString(R.string.programs_not_found, query),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            override fun onFailure(call: Call<Records>, t: Throwable) {
                // 通信エラーなどで応答がなかったら接続できませんでしたメッセージを出す
                if(showErrorToast) {
                    Toast.makeText(
                        context!!,
                        R.string.connect_epgstation_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row
        ) {
            when (item) {
                is GetRecordedParam -> {
                    // EPGStation Version 1.x.x の続きを取得するアイテム
                    val adapter =  ((row as ListRow).adapter as ArrayObjectAdapter)

                    //APIで続きを取得して続きに加えていく
                    // EPGStation V1.x.x
                    EpgStation.api?.getRecorded(
                        limit = item.limit,
                        offset = item.offset,
                        reverse = item.reverse,
                        rule = item.rule,
                        genre1 = item.genre1,
                        channel = item.channel,
                        keyword = item.keyword,
                        hasTs = item.hasTs,
                        recording = item.recording
                    )?.enqueue(object : Callback<GetRecordedResponse> {
                        override fun onResponse(call: Call<GetRecordedResponse>, response: Response<GetRecordedResponse>) {
                            response.body()?.let { getRecordedResponse ->

                                //APIのレスポンスをひとつづつアイテムとして加える。最初のアイテムだけ、Loadingアイテムを置き換える
                                //先にremoveしてaddすると高速でスクロールさせたときに描画とremoveがぶつかって落ちるのであえてreplaceに。
                                getRecordedResponse.recorded.forEachIndexed {  index, recordedProgram ->
                                    if(index == 0) {
                                        adapter.replace(adapter.indexOf(item),recordedProgram)
                                    }else{
                                        adapter.add(recordedProgram)
                                    }
                                }
                                //続きがあるなら"次を読み込む"を置く。
                                val numOfItem = getRecordedResponse.recorded.count().toLong() + item.offset
                                if (numOfItem < getRecordedResponse.total) {
                                    adapter.add(item.copy(offset = numOfItem))
                                }

                            }
                        }
                        override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                        }
                    })
                }
                is GetRecordedParamV2 -> {
                    // EPGStation Version 1.x.x の続きを取得するアイテム
                    val adapter =  ((row as ListRow).adapter as ArrayObjectAdapter)

                    //APIで続きを取得して続きに加えていく
                    // EPGStation V2.x.x
                    EpgStationV2.api?.getRecorded(
                        isHalfWidth = item.isHalfWidth,
                        offset = item.offset,
                        limit = item.limit,
                        isReverse = item.isReverse,
                        ruleId = item.ruleId,
                        channelId = item.channelId,
                        genre = item.genre,
                        keyword = item.keyword,
                        hasOriginalFile = item.hasOriginalFile
                    )?.enqueue(object : Callback<Records> {
                        override fun onResponse(call: Call<Records>, response: Response<Records>) {
                            response.body()?.let { responseBody ->

                                //APIのレスポンスをひとつづつアイテムとして加える。最初のアイテムだけ、Loadingアイテムを置き換える
                                //先にremoveしてaddすると高速でスクロールさせたときに描画とremoveがぶつかって落ちるのであえてreplaceに。
                                responseBody.records.forEachIndexed {  index, recordedProgram ->
                                    if(index == 0) {
                                        adapter.replace(adapter.indexOf(item),recordedProgram)
                                    }else{
                                        adapter.add(recordedProgram)
                                    }
                                }
                                //続きがあるなら"次を読み込む"を置く。
                                val numOfItem = responseBody.records.count().toLong() + item.offset
                                if (numOfItem < responseBody.total) {
                                    adapter.add(item.copy(offset = numOfItem))
                                }

                            }
                        }
                        override fun onFailure(call: Call<Records>, t: Throwable) {
                            Log.d(TAG,"loadRows() getRecorded API Failure")
                            Toast.makeText(context!!, getString(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
                        }
                    })
                }

            }
        }
    }

    private fun addHistory(keyword:String){
        val pref = PreferenceManager.getDefaultSharedPreferences(context)
        val edit = pref.edit()
        var histories = pref.getString("pref_key_search_histories", "")
        histories?.let{
            if(histories == "") {
                histories = keyword
            }else{
                histories = histories + HISTORY_DELIMITER + keyword
            }
            //ヒストリーが設定数より多かったら切り詰める。
            val arrayOfHistory = histories!!.split(HISTORY_DELIMITER)
            val historySize = pref.getString(getString(R.string.pref_key_num_of_history),getString(R.string.pref_val_num_of_history_default))!!.toInt()
            if (arrayOfHistory.size > historySize){
                histories = arrayOfHistory.drop(arrayOfHistory.size-historySize).joinToString(HISTORY_DELIMITER)
            }

        }
        edit.putString("pref_key_search_histories",histories)
        edit.apply()

    }


    companion object {
        private const val TAG = "SearchFragment"
        private const val AMAZON_FEATURE_FIRE_TV = "amazon.hardware.fire_tv"
        private const val HISTORY_DELIMITER = "|"

        fun getHistory(_context: Context): List<String> {
            val pref = PreferenceManager.getDefaultSharedPreferences(_context)
            val histories = pref.getString("pref_key_search_histories", "")

            if (histories ==""){
                return listOf<String>()
            }

            val listOfHistory = histories!!.split(HISTORY_DELIMITER)
            val historySize = pref.getString(_context.getString(R.string.pref_key_num_of_history),_context.getString(R.string.pref_val_num_of_history_default))!!.toInt()
            if (listOfHistory.size > historySize){
                return listOfHistory.drop(listOfHistory.size-historySize)
            }
            return listOfHistory

        }

    }
}