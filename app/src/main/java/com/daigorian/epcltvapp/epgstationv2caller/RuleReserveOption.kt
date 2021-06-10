package com.daigorian.epcltvapp.epgstationv2caller
//ルール予約オプション
data class RuleReserveOption(
    val enable	:Boolean?,//    ルールが有効か
    val allowEndLack	:Boolean?,//    末尾切れを許可するか
    val avoidDuplicate	:Boolean?,//    録画済みの重複番組を排除するか
    val periodToAvoidDuplicate:	Long?, //    重複を避ける期間
    val tags	:List<Long>? //    重複を避ける期間
)
