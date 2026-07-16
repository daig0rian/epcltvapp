package com.daigorian.epcltvapp.epgstationv2caller

data class ConfigResponse(val streamConfig: StreamConfig? = null)

data class StreamConfig(
    val live: LiveStreamConfig? = null,
    val recorded: RecordedStreamConfig? = null
)

data class LiveStreamConfig(val ts: LiveTsConfig? = null)

data class LiveTsConfig(
    val m2ts: List<M2tsStreamParam> = emptyList(),
    val hls: List<String> = emptyList()
)

data class M2tsStreamParam(
    val name: String = "",
    val isUnconverted: Boolean = false
)

data class RecordedStreamConfig(val ts: RecordedTsConfig? = null)

data class RecordedTsConfig(val hls: List<String> = emptyList())
