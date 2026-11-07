package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.ServerCompatibility.AfterVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ServerCompatibility] の回帰テスト。接続先の応答から、接続する・v1 として断る・接続失敗の
 * どれになるかを確かめる。
 */
class ServerCompatibilityTest {

    @Test
    fun `v2 と v3 の番号には接続する`() {
        assertEquals(AfterVersion.Connect("2.10.0"), ServerCompatibility.afterVersion(200, true, "2.10.0"))
        assertEquals(
            AfterVersion.Connect("3.0.0-beta4"),
            ServerCompatibility.afterVersion(200, true, "3.0.0-beta4")
        )
    }

    @Test
    fun `この先のメジャー番号にも接続する`() {
        assertEquals(AfterVersion.Connect("4.0.0"), ServerCompatibility.afterVersion(200, true, "4.0.0"))
    }

    @Test
    fun `番号が読めなくても接続する`() {
        assertEquals(AfterVersion.Connect("develop"), ServerCompatibility.afterVersion(200, true, "develop"))
        assertEquals(AfterVersion.Connect(null), ServerCompatibility.afterVersion(200, true, null))
    }

    @Test
    fun `メジャー番号が2より小さければ接続しない`() {
        assertEquals(AfterVersion.Fail, ServerCompatibility.afterVersion(200, true, "1.7.6"))
    }

    @Test
    fun `version が 404 なら v1 かを確かめに行く`() {
        assertEquals(AfterVersion.CheckV1, ServerCompatibility.afterVersion(404, false, null))
    }

    @Test
    fun `404 以外のエラーは v1 を疑わずに接続失敗にする`() {
        // 以前はサーバーエラー(5xx)でも v1 として扱い、v2 のサーバーなのにホームが欠けたまま開いていた。
        assertEquals(AfterVersion.Fail, ServerCompatibility.afterVersion(500, false, null))
        assertEquals(AfterVersion.Fail, ServerCompatibility.afterVersion(503, false, null))
        assertEquals(AfterVersion.Fail, ServerCompatibility.afterVersion(401, false, null))
    }

    @Test
    fun `本文の無い成功応答は接続失敗にする`() {
        assertEquals(AfterVersion.Fail, ServerCompatibility.afterVersion(204, false, null))
    }

    @Test
    fun `storage が 200 なら v1`() {
        assertTrue(ServerCompatibility.isV1(200))
        assertFalse(ServerCompatibility.isV1(404))
        assertFalse(ServerCompatibility.isV1(401))
        assertFalse(ServerCompatibility.isV1(500))
    }

    @Test
    fun `メジャー番号は先頭の数字から読む`() {
        assertEquals(2, ServerCompatibility.majorOf("2.10.0"))
        assertEquals(3, ServerCompatibility.majorOf("3.0.0-beta4"))
        assertEquals(10, ServerCompatibility.majorOf(" 10.1 "))
        assertNull(ServerCompatibility.majorOf("v2.1"))
        assertNull(ServerCompatibility.majorOf(""))
        assertNull(ServerCompatibility.majorOf(null))
    }
}
