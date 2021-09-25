package com.daigorian.epcltvapp.epgstationv2caller

import retrofit2.http.Query

data class GetRecordedParamV2(
    val isHalfWidth: Boolean = true,
    val offset: Long = 0,
    val limit: Long = EpgStationV2.default_limit.toLong(),
    val isReverse: Boolean = false,
    val ruleId: Long? = null,
    val channelId: Long? = null,
    val genre: Long? = null,
    val keyword: String? = null,
    val hasOriginalFile: Boolean? = null,
)
