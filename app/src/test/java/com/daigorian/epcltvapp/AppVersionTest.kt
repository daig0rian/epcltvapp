package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.githubcaller.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppVersion] のテスト。
 *
 * 見ているのは主に「更新があると誤判定しないこと」。誤って更新ありに倒すと、
 * 利用者をダウンロードとインストール確認まで連れて行った末に何も起きない、という
 * 一番わかりにくい失敗になるため。
 */
class AppVersionTest {

    // --- parse ---

    @Test
    fun 先頭のvを落として数値列にする() {
        assertEquals(listOf(1, 37), AppVersion.parse("v1.37"))
        assertEquals(listOf(1, 37), AppVersion.parse("1.37"))
        assertEquals(listOf(1, 37, 1), AppVersion.parse("v1.37.1"))
    }

    @Test
    fun 前後の空白は無視する() {
        assertEquals(listOf(1, 37), AppVersion.parse("  v1.37  "))
    }

    @Test
    fun 数値以外が混ざる形は解釈しない() {
        assertNull(AppVersion.parse("v1.38-beta"))
        assertNull(AppVersion.parse("1.38rc1"))
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun 空や欠けた成分は解釈しない() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("1..2"))
        assertNull(AppVersion.parse("1."))
    }

    // --- isNewer ---

    @Test
    fun 新しいタグは更新ありと判定する() {
        assertTrue(AppVersion.isNewer("v1.38", "1.37"))
        assertTrue(AppVersion.isNewer("v2.0", "1.37"))
        assertTrue(AppVersion.isNewer("v1.37.1", "1.37"))
    }

    @Test
    fun 同じバージョンは更新なし() {
        assertFalse(AppVersion.isNewer("v1.37", "1.37"))
    }

    @Test
    fun 桁数が違っても足りない側を0として比べる() {
        assertFalse(AppVersion.isNewer("v1.37.0", "1.37"))
        assertFalse(AppVersion.isNewer("v1.37", "1.37.0"))
    }

    @Test
    fun 古いタグは更新なし() {
        // インストール済みが公開版より新しい場合 (開発ビルドなど)。
        assertFalse(AppVersion.isNewer("v1.37", "1.38"))
    }

    @Test
    fun 辞書順ではなく数値で比べる() {
        // 文字列比較だと "1.9" > "1.10" になってしまう。
        assertTrue(AppVersion.isNewer("v1.10", "1.9"))
        assertFalse(AppVersion.isNewer("v1.9", "1.10"))
    }

    @Test
    fun 解釈できない入力は更新なしに倒す() {
        assertFalse(AppVersion.isNewer("v1.38-beta", "1.37"))
        assertFalse(AppVersion.isNewer("v1.38", "unknown"))
        assertFalse(AppVersion.isNewer(null, "1.37"))
        assertFalse(AppVersion.isNewer("v1.38", null))
    }

    // --- display ---

    @Test
    fun 表示用は先頭のvだけ落とす() {
        assertEquals("1.37", AppVersion.display("v1.37"))
        assertEquals("1.37", AppVersion.display("1.37"))
        assertEquals("", AppVersion.display(null))
    }

    // --- pickApkAsset ---

    private fun asset(name: String?, url: String? = "https://example.invalid/$name") =
        GitHubRelease.Asset(name = name, browserDownloadUrl = url, size = 1L)

    @Test
    fun CIが付ける名前のAPKを優先する() {
        val picked = AppVersion.pickApkAsset(
            listOf(asset("mapping.txt"), asset("other.apk"), asset("app-release.apk"))
        )
        assertEquals("app-release.apk", picked?.name)
    }

    @Test
    fun 名前が違ってもapkがあれば拾う() {
        val picked = AppVersion.pickApkAsset(listOf(asset("mapping.txt"), asset("epcltvapp-1.38.apk")))
        assertEquals("epcltvapp-1.38.apk", picked?.name)
    }

    @Test
    fun URLのない添付は選ばない() {
        assertNull(AppVersion.pickApkAsset(listOf(asset("app-release.apk", url = null))))
        assertNull(AppVersion.pickApkAsset(listOf(asset("app-release.apk", url = ""))))
    }

    @Test
    fun apkがなければnull() {
        assertNull(AppVersion.pickApkAsset(listOf(asset("mapping.txt"))))
        assertNull(AppVersion.pickApkAsset(emptyList()))
        assertNull(AppVersion.pickApkAsset(null))
    }
}
