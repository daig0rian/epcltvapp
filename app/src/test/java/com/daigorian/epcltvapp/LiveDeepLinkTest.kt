package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LiveDeepLink] のテスト。
 *
 * 受け付けるべきものを受け付けることより、**受け付けてはいけないものを弾くこと**を重く見ている。
 * 解釈できない URI を黙って既定チャンネルへ倒すと、利用者は誤りに気づけないため。
 */
class LiveDeepLinkTest {

    private fun parse(uri: String): LiveDeepLink.Target? {
        // "scheme://host/seg/seg" を Android の Uri に頼らず分解する
        val schemeEnd = uri.indexOf("://")
        val scheme = if (schemeEnd >= 0) uri.substring(0, schemeEnd) else null
        val rest = if (schemeEnd >= 0) uri.substring(schemeEnd + 3) else uri
        val parts = rest.split("/")
        val host = parts.firstOrNull()
        val segments = parts.drop(1).filter { it.isNotEmpty() }
        return LiveDeepLink.parse(scheme, host, segments)
    }

    @Test
    fun channelIdを解釈する() {
        assertEquals(
            LiveDeepLink.Target.ByChannelId(3273601024L),
            parse("epcltvapp://live/channelId/3273601024")
        )
    }

    @Test
    fun 末尾のスラッシュがあっても解釈する() {
        assertEquals(
            LiveDeepLink.Target.ByChannelId(1L),
            parse("epcltvapp://live/channelId/1/")
        )
    }

    @Test
    fun schemeとhostの大小文字は問わない() {
        assertEquals(
            LiveDeepLink.Target.ByChannelId(42L),
            parse("EPCLTVAPP://LIVE/channelId/42")
        )
    }

    @Test
    fun 別のschemeは受け付けない() {
        assertNull(parse("https://live/channelId/1"))
    }

    @Test
    fun 知らないhostは受け付けない() {
        assertNull(parse("epcltvapp://recorded/channelId/1"))
    }

    @Test
    fun 知らない識別子の種別は受け付けない() {
        // 将来 remoteControlKeyId を足すまでは解釈できない。既定値へ倒さず null を返すこと。
        assertNull(parse("epcltvapp://live/remoteControlKeyId/1"))
    }

    @Test
    fun 種別を省略した形は受け付けない() {
        assertNull(parse("epcltvapp://live/3273601024"))
    }

    @Test
    fun 数値でないchannelIdは受け付けない() {
        assertNull(parse("epcltvapp://live/channelId/abc"))
    }

    @Test
    fun channelIdが空なら受け付けない() {
        assertNull(parse("epcltvapp://live/channelId/"))
    }

    @Test
    fun 余分なパスがあれば受け付けない() {
        assertNull(parse("epcltvapp://live/channelId/1/extra"))
    }
}
