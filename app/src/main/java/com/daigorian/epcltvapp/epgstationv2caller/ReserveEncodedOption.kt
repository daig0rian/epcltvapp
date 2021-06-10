package com.daigorian.epcltvapp.epgstationv2caller
//予約エンコードオプション
data class ReserveEncodedOption(
    val mode1	:String?,//    エンコードモード1
    val encodeParentDirectoryName1	:String?,//    エンコードモード1親ディレクトリ
    val directory1	:String?,//    エンコードモード1ディレクトリ
    val mode2	:String?,//    エンコードモード2
    val encodeParentDirectoryName2	:String?,//    エンコードモード2親ディレクトリ
    val directory2	:String?,//    エンコードモード2ディレクトリ
    val mode3	:String?,//    エンコードモード3
    val encodeParentDirectoryName3	:String?,//    エンコードモード3親ディレクトリ
    val directory3	:String?,//    エンコードモード3ディレクトリ
    val isDeleteOriginalAfterEncode	:Boolean?,//エンコード後に ts を削除するか
)
