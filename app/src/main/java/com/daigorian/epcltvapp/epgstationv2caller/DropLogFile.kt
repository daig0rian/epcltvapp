package com.daigorian.epcltvapp.epgstationv2caller

data class DropLogFile(
    val id:Long = 0,
    val errorCnt:Long = 0,
    val dropCnt:Long = 0,
    val scramblingCnt:Long = 0,
)
