package com.daigorian.epcltvapp.epgstationv2caller
//予約保存オプション
data class ReserveSaveOption(
    val parentDirectoryName	:String?,//    親保存ディレクトリ
    val directory	:String?,//    保存ディレクトリ
    val recordedFormat	:String?,//    ファイル名フォーマット
)
