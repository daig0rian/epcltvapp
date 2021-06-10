package com.daigorian.epcltvapp.epgstationv2caller

//	録画情報
data class Records(
    val records: List<RecordedItem>,
    val total: Int //録画総件数
)