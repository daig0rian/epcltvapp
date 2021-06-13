package com.daigorian.epcltvapp.epgstationcaller

data class GetRecordedParam(
    val limit: Long = EpgStation.default_limit.toLong(),
    val offset: Long = 0,
    val reverse: Boolean = false,
    val rule: Long? = null,
    val genre1: Long? = null,
    val channel: Long? = null,
    val keyword: String? = null,
    val hasTs: Boolean? = null,
    val recording: Boolean? = null

)