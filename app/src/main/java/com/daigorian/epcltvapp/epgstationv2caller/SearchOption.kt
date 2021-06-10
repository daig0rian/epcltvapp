package com.daigorian.epcltvapp.epgstationv2caller

data class SearchOption(
    val id	:Long?, //rule id
    val keyword	:String?, //検索文字列
    val ignoreKeyword	:String?, //検索除外文字列
    val keyCS	:Boolean?, //大文字小文字をキーワードで区別する
    val keyRegExp	:Boolean?, //正規表現(キーワード)
    val title	:Boolean?, //タイトルをキーワード検索範囲に含む
    val description	:Boolean?, //詳細をキーワード検索範囲に含む
    val extended	:Boolean?, //拡張をキーワード検索範囲に含む
    val ignoreKeyCS	:Boolean?, //大文字小文字を除外キーワードで区別する
    val ignoreKeyRegExp	:Boolean?, //正規表現(除外キーワード)
    val ignoreTitle	:Boolean?, //タイトルを除外キーワード検索範囲に含む
    val ignoreDescription	:Boolean?, //詳細を除外キーワード検索範囲に含む
    val ignoreExtended	:Boolean?, //拡張を除外キーワード検索範囲に含む
    val GR	:Boolean?, //地上波
    val BS	:Boolean?, //BS
    val CS	:Boolean?, //CS
    val SKY	:Boolean?, //SKY
    val station	:Long?, //maximum: 6553565535
    val genrelv1	:Long?, //
    val genrelv2	:Long?, //
    val startTime	:Long?, //minimum: 0 開始時刻
    val timeRange	:Long?, //minimum: 1 時刻範囲
    val week	:Long?, //曜日 0x01, 0x02, 0x04, 0x08, 0x10, 0x20 ,0x40 が日〜土に対応するので and 演算で曜日を取り出せる
    val isFree	:Boolean?, //無料放送だけか
    val durationMin	:Long?, //最小長
    val durationMax	:Long?, //最大長
    val enable	:Boolean?, //ルールが有効か
    val allowEndLack	:Boolean?, //チューナの使用状況によっては末尾が欠けることを許可
    val directory	:String?, //録画データの保存場所
    val recordedFormat	:String?, //録画ファイル名のフォーマット
    val mode1	:Long?, //録画モード 1
    val directory1	:String?, //録画モード 1 の保存場所
    val mode2	:Long?, //録画モード 2
    val directory2	:String?, //録画モード 2 の保存場所
    val mode3	:Long?, //録画モード 3
    val directory3	:String?, //録画モード 3 の保存場所
    val delTs	:Boolean?, //エンコード後にオリジナルファイルを削除するか
)
