package com.daigorian.epcltvapp.epgstationv2caller

// ルール検索オプション
data class RuleSearchOption(
    val keyword:String?, //    検索キーワード
    val ignoreKeyword	:String?, //    除外検索キーワード
    val keyCS	:Boolean?, //     大文字小文字区別有効化 (検索キーワード)
    val keyRegExp	:Boolean?, // 正規表現 (検索キーワード)
    val name	:Boolean?, // 番組名 (検索キーワード)
    val description	:Boolean?, // 概要 (検索キーワード)
    val extended	:Boolean?, // 詳細 (検索キーワード)
    val ignoreKeyCS	:Boolean?, // 大文字小文字区別有効化 (除外検索キーワード)
    val ignoreKeyRegExp	:Boolean?, // 正規表現 (除外検索キーワード)
    val ignoreName	:Boolean?, // 番組名 (除外検索キーワード)
    val ignoreDescription	:Boolean?, // 概要 (除外検索キーワード)
    val ignoreExtended	:Boolean?, // 詳細 (除外検索キーワード)
    val GR	:Boolean?, // GR
    val BS	:Boolean?, // BS
    val CS	:Boolean?, // CS
    val SKY	:Boolean?, // SKY
    val channelIds:List<Long>?,// 放送局 idの配列
    val genres:List<Genre>?, //ジャンル
    val times:List<SearchTime>?,	 //時刻範囲
    val isFree	:Boolean?, //無料放送か
    val durationMin:Long?,  //番組最小時間 (分)
    val durationMax:Long?, //番組最大時間 (分)
    val searchPeriods:List<SearchPeriod>?,	//検索対象期間
)
