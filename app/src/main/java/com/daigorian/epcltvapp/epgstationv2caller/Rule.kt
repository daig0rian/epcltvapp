package com.daigorian.epcltvapp.epgstationv2caller
//ルール
data class Rule(
    val isTimeSpecification:	Boolean?, //    時刻指定予約か
    val searchOption:	RuleSearchOption?,
    val reserveOption:	RuleReserveOption?,
    val saveOption:		ReserveSaveOption?,
    val encodeOption:	ReserveEncodedOption?,
    val id:	Long, //ルール id
    val reservesCnt:	Long //予約件数
)
