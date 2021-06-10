package com.daigorian.epcltvapp.epgstationv2caller
//タグ情報

import java.io.Serializable

data class RecordedTag(
    val id:Long = 0, //録画 tag id
    val name:String?, //タグ名
    val color:String //色
): Serializable {

    override fun toString(): String {
        return "RecordedTag{" +
                "name=" + name +
                '}'
    }

    companion object {
        internal const val serialVersionUID = 0L
    }
}
