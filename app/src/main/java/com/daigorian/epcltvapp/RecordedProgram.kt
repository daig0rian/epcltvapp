package com.daigorian.epcltvapp

import java.io.Serializable

data class RecordedProgram (

    val id : Long = 0,
    val programId : Long = 0,
    val channelId : Long = 0,
    val channelType : String = "",
    val startAt : Long = 0,
    val endAt : Long = 0,
    val name : String= "no name",
    val description : String? = null,
    val extended : String? = null,
    val genre1 : Long? = null,
    val genre2 : Long? = null,
    val genre3 : Long? = null,
    val genre4 : Long? = null,
    val genre5 : Long? = null,
    val genre6 : Long? = null,
    val videoType : String? = null,
    val videoResolution : String? = null,
    val videoStreamContent : Long? = null,
    val videoComponentType : Long? = null,
    val audioSamplingRate : Long? = null,
    val audioComponentType : Long? = null,
    val ruleId : Long? = null,
    val recording : Boolean = false,
    val protection : Boolean = false,
    val filesize : Long? = null,
    val errorCnt : Long? = null,
    val dropCnt : Long? = null,
    val scramblingCnt : Long? = null,
    val isTmp : Boolean? = null,
    val hasThumbnail : Boolean,
    val original : Boolean = false,
    val filename : String? = null,
    val encoded : List<EncodedProgram>? = null,
    val encoding : List<RecordedEncodingInfo>? = null
): Serializable {

    override fun toString(): String {
        return "RecordedProgram{" +
                "id=" + id +
                ", programId='" + programId + '\'' +
                ", channelId='" + channelId + '\'' +
                ", channelType='" + channelType + '\'' +
                ", startAt='" + startAt + '\'' +
                ", endAt='" + endAt + '\'' +
                ", name='" + name + '\'' +
                '}'
    }

    companion object {
        internal const val serialVersionUID = 0L
    }
}
