package com.daigorian.epcltvapp

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.leanback.widget.*
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.GetRecordedParamV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
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
                    var horizontalDeletedCount = 0
                    while(horizontalIndex < it.size()) {

                        when(it.get(horizontalIndex)){
                            //アイテムが見つかったら削除する
                            is RecordedItem ->{
                                if(item is RecordedItem &&
                                    (it.get(horizontalIndex) as RecordedItem).id == item.id){
                                    it.removeItems(horizontalIndex,1)
                                    horizontalIndex -= 1
                                    horizontalDeletedCount += 1
                                }
                            }
                            //最後尾にある「続きを読み込む」アイテムがきたら、削除した分だけオフセットを下げる
                            is GetRecordedParamV2 -> {
                                val currentItem = it.get(horizontalIndex) as GetRecordedParamV2
                                val newItem = GetRecordedParamV2(
                                    isHalfWidth = currentItem.isHalfWidth,
                                    offset = currentItem.offset - horizontalDeletedCount,
                                    limit = currentItem.limit,
                                    isReverse = currentItem.isReverse,
                                    ruleId = currentItem.ruleId,
                                    channelId = currentItem.channelId,
                                    genre = currentItem.genre,
                                    keyword = currentItem.keyword,
                                    hasOriginalFile = currentItem.hasOriginalFile,
                                )
                                it.replace(horizontalIndex,newItem)
                            }
                            //現状上記以外のものは入らないはずだが、来たらitemが同じ時に限ってけしてやる。
                            else ->{
                                if(it.get(horizontalIndex).equals(item)){
                                    it.removeItems(horizontalIndex,1)
                                    horizontalIndex -= 1
                                    horizontalDeletedCount += 1
                                }
                            }
                        }// when
                        horizontalIndex += 1
                    }// while(horizontalIndex < it.size())
                }

            }
            verticalIndex += 1
        }

    }
    fun getListRowByHeaderId(headerId : Long):ListRow?{
        var verticalIndex = 0
        while(verticalIndex < size()) {
            val row = get(verticalIndex)
            if(row is ListRow){
                if(row.headerItem.id == headerId){
                    return row
                }
            }
            verticalIndex += 1
        }
        return null
    }

    fun updateContentsListRow(v2Param: GetRecordedParamV2, title:String, id:Long,presenter:Presenter,context: Context){


        // 同じIDを持つ行が存在するかどうか確認する
        val listRow = getListRowByHeaderId(id)

        // 既存の行があれば、それを取得する。なければ新たに作る。
        val listRowAdapter = if(listRow==null)
            ArrayObjectAdapter(presenter)
        else
            listRow.adapter as ArrayObjectAdapter

        // 既存の行がなければ、新たに作った行を追加する。
        if(listRow==null){
            val header = HeaderItem( id ,title)
            add(ListRow(header, listRowAdapter))
        }

        // すでにロードされている数。
        val numOfLoaded = if (listRow==null)
            0L
        else
            listRowAdapter.size().toLong()

        // APIのコールバックでListRowの中身をセットするように仕掛ける
        EpgStationV2.api?.getRecorded(
            isHalfWidth = v2Param.isHalfWidth,
            offset = v2Param.offset,
            limit = if(numOfLoaded>v2Param.limit) numOfLoaded else v2Param.limit ,
            isReverse = v2Param.isReverse,
            ruleId = v2Param.ruleId,
            channelId = v2Param.channelId,
            genre = v2Param.genre,
            keyword = v2Param.keyword,
            hasOriginalFile = v2Param.hasOriginalFile )?.enqueue(object : Callback<Records> {
            override fun onResponse(call: Call<Records>, response: Response<Records>) {
                response.body()?.let { getRecordedResponse ->

                    //既存のリストにあって、レスポンスにないアイテムの削除
                    var horizontalIndex = 0
                    while(horizontalIndex < listRowAdapter.size()) {
                        var found = false
                        getRecordedResponse.records.forEach {
                            if(listRowAdapter.get(horizontalIndex).equals(it) ) found = true
                        }
                        if (!found) {
                            listRowAdapter.removeItems(horizontalIndex,1)
                        }else {
                            horizontalIndex += 1
                        }
                    }

                    //レスポンスにあって、既存のリストにないアイテムの追加
                    getRecordedResponse.records.forEachIndexed { index, it ->
                        if(listRowAdapter.indexOf(it) == -1){
                            listRowAdapter.add(index,it)
                        }
                    }
                    //続きがあるなら"次を読み込む"を置く。
                    val numOfItem = getRecordedResponse.records.count().toLong()
                    if (numOfItem < getRecordedResponse.total) {
                        listRowAdapter.add(
                            GetRecordedParamV2(
                            isHalfWidth = v2Param.isHalfWidth,
                            offset = numOfItem,
                            limit = v2Param.limit,
                            isReverse = v2Param.isReverse,
                            ruleId = v2Param.ruleId,
                            channelId = v2Param.channelId,
                            genre = v2Param.genre,
                            keyword = v2Param.keyword,
                            hasOriginalFile = v2Param.hasOriginalFile)
                        )
                    }
                }
            }
            override fun onFailure(call: Call<Records>, t: Throwable) {
                Log.d(TAG,"updateContentsListRow() getRecorded API Failure")
                Toast.makeText(context, context.getText(R.string.connect_epgstation_failed), Toast.LENGTH_LONG).show()
            }
        })

    }
    companion object{
        private const val TAG = "D.E.ArrayObjectAdapter"
    }


}