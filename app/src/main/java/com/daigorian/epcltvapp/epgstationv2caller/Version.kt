package com.daigorian.epcltvapp.epgstationv2caller

data class Version(
    // Gson は応答に無い項目を null のまま入れるので、型でもそれを許す。
    val version :String?
)
