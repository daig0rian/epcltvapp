package com.daigorian.epcltvapp.epgstationv2caller

import java.io.Serializable

//ビデオファイル情報
data class VideoFile(
    val id : Long, //ビデオファイル id
    val name  : String, //ビデオ名 (Web上の表示名)
    val filename  : String?, //ビデオファイル名
    val type  : String,//ビデオファイル形式[ ts, encoded ]
    val size  : Long, //ファイルサイズ
): Serializable {

    override fun toString(): String {
        return "VideoFile{" +
                "name=" + name +
                '}'
    }

    companion object {
        internal const val serialVersionUID = 0L
    }
}
