package com.daigorian.epcltvapp.epgstationv2caller

data class ManualReserveOption(
    val programId: Long,
    val allowEndLack: Boolean = true
)
