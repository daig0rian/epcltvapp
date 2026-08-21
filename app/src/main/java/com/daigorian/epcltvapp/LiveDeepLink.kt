package com.daigorian.epcltvapp

/**
 * ライブ視聴を外部から開始するための deep link を解釈する。
 *
 *     epcltvapp://live/channelId/<channelId>
 *
 * 識別子の種別をパスに含めているのは、将来 remoteControlKeyId のような別の指定方法を
 * 足せるようにするため。種別を増やすときは [Target] にケースを足し、[parse] の when に
 * 分岐を追加する。
 *
 * Android の Uri に依存しない純粋関数として置く。呼び出し側が Uri から scheme / host /
 * pathSegments を取り出して渡す。
 */
object LiveDeepLink {

    const val SCHEME = "epcltvapp"

    private const val HOST_LIVE = "live"
    private const val TYPE_CHANNEL_ID = "channelId"

    /** 解釈できた指定内容。 */
    sealed class Target {
        /** EPGStation のチャンネルID(サービスID)で指定する。 */
        data class ByChannelId(val channelId: Long) : Target()
    }

    /**
     * @return 解釈できた場合は [Target]。この deep link として扱えない場合は null。
     *         null は「利用者に伝えるべき誤り」であって、黙って既定値へ倒してはならない。
     */
    fun parse(scheme: String?, host: String?, pathSegments: List<String>): Target? {
        if (!SCHEME.equals(scheme, ignoreCase = true)) return null
        if (!HOST_LIVE.equals(host, ignoreCase = true)) return null
        if (pathSegments.size != 2) return null

        val type = pathSegments[0]
        val value = pathSegments[1]

        return when {
            TYPE_CHANNEL_ID.equals(type, ignoreCase = true) ->
                value.toLongOrNull()?.let { Target.ByChannelId(it) }

            else -> null
        }
    }
}
