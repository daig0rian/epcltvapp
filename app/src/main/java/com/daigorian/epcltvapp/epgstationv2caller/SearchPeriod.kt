package com.daigorian.epcltvapp.epgstationv2caller
//検索対象期間オプション
data class SearchPeriod(
    val startAt:Long?,//    時刻 (ms)
    val endAt:Long?//時刻 (ms)
)
