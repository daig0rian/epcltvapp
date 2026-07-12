package com.daigorian.epcltvapp.epgstationv2caller

data class ChannelItem(
    val id: Long = 0,
    val name: String = "",
    val halfWidthName: String = ""
) {
    // 「番組名更新」で取得した現在放送中の番組名。equals/hashCode/copy には含めない副次的な状態。
    @Transient
    var currentProgramName: String? = null
}
