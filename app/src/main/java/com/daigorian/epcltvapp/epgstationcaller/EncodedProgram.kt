package com.daigorian.epcltvapp.epgstationcaller

import java.io.Serializable

data class EncodedProgram (

    val encodedId : Long,
    val name : String,
    val filename : String,
    val filesize : Long? = null,
): Serializable {

    override fun toString(): String {
        return "EncodedProgram{" +
                "encodedId=" + encodedId +
                ", name='" + name + '\'' +
                ", filename='" + filename + '\'' +
                ", filesize='" + filesize + '\'' +
                '}'
    }

    companion object {
        internal const val serialVersionUID = 0L
    }
}

