package com.daigorian.epcltvapp.epgstationv2caller

data class Schedule(
    val channel: ScheduleChannelItem,
    val programs: List<ScheduleProgramItem>
)

data class ScheduleChannelItem(
    val id: Long = 0
)

data class ScheduleProgramItem(
    val id: Long = 0,
    val channelId: Long = 0,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val name: String = "",
    val description: String? = null,
    val extended: String? = null,
    val genre1: Long? = null,
    val subGenre1: Long? = null
)
