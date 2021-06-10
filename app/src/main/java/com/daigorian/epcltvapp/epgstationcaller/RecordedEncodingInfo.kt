package com.daigorian.epcltvapp.epgstationcaller

import java.io.Serializable

data class RecordedEncodingInfo(
    val name : String,
    val isEncoding: Boolean,
): Serializable {

    override fun toString(): String {
        return "RecordedEncodingInfo{" +
                "name=" + name +
                ", isEncoding='" + isEncoding + '\'' +
                '}'
    }

    companion object {
        internal const val serialVersionUID = 0L
    }
}


