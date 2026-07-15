package com.daigorian.epcltvapp.epgstationv2caller

data class ChannelItem(
    val id: Long = 0,
    val name: String = "",
    val halfWidthName: String = "",
    val type: Int? = null
) {
    // 「番組名更新」で取得した現在放送中の番組名。equals/hashCode/copy には含めない副次的な状態。
    @Transient
    var currentProgramName: String? = null

    companion object {
        /**
         * 映像・音声サービスであるかを返す（データ放送専用サービス等を除外する）。
         * EPGStation公式Web UIのChannelModel.isAudioVideoServiceと同じ基準。
         * @see https://github.com/DBCTRADO/LibISDB/blob/master/LibISDB/LibISDBConsts.hpp#L122
         */
        fun isAudioVideoService(type: Int?): Boolean {
            return when (type) {
                0x01, 0x02, 0xa1, 0xa2, 0xa5, 0xa6, 0xad, null -> true
                else -> false
            }
        }
    }
}
