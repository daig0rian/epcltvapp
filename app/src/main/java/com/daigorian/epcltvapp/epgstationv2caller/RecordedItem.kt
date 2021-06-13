package com.daigorian.epcltvapp.epgstationv2caller

import java.io.Serializable

//録画番組情報
data class RecordedItem (
    val id	:Long, //録画済み番組 id
    val ruleId	:Long?, //ルール id
    val programId	:Long?, //maximum: 655356553565535 program id
    val channelId	:Long?, //maximum: 6553565535 放送局 id
    val startAt	:Long, //時刻 (ms)
    val endAt	:Long, //時刻 (ms)
    val name	:String, //番組名
    val description	:String?, //番組詳細
    val extended	:String?, //番組拡張
    val genre1	:Long?, //ジャンル
    val subGenre1	:Long?, //サブジャンル
    val genre2	:Long?, //ジャンル
    val subGenre2	:Long?, //サブジャンル
    val genre3	:Long?, //ジャンル
    val subGenre3	:Long?, //サブジャンル
    val videoType	:String?, //番組ビデオコーデック[ mpeg2, h.264, h.265 ]
    val videoResolution	:String?, //番組ビデオ解像度 [ 240p, 480i, 480p, 720p, 1080i, 1080p, 2160p, 4320p ]
    val videoStreamContent	:Long?,
    val videoComponentType	:Long?,
    val audioSamplingRate	:Long?, //番組オーディオサンプリングレート[ 16000, 22050, 24000, 32000, 44100, 48000 ]
    val audioComponentType	:Long?, //
    val isRecording	:Boolean, //録画中か
    val thumbnails	:List<Long>?, //サムネイル id
    val videoFiles	:List<VideoFile>?,//	ビデオファイル情報
    val dropLog	:DropLogFile?, //ドロップログファイル情報
    val tags	:List<RecordedTag>?,//タグ情報
    val isEncoding	:Boolean, //エンコード中か
    val isProtected	:Boolean, //自動録画削除対象外か
): Serializable {

    override fun toString(): String {
        return "RecordedItem{" +
                "id=" + id +
                ", programId='" + programId + '\'' +
                ", channelId='" + channelId + '\'' +
                ", startAt='" + startAt + '\'' +
                ", endAt='" + endAt + '\'' +
                ", name='" + name + '\'' +
                '}'
    }

    companion object {
        internal const val serialVersionUID = 0L
    }
}
