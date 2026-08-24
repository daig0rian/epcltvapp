package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.githubcaller.GitHubRelease
import com.daigorian.epcltvapp.githubcaller.GitHubReleaseApi

/**
 * アプリのバージョン文字列の比較と、リリースに添付された APK の選別。
 *
 * Android にも GitHub にも依存しない純関数だけを置く (単体テストのため)。
 *
 * **解釈できない入力はすべて「更新なし」に倒す。** バージョン表記を読み違えて
 * 「更新がある」と誤判定すると、利用者を無駄なダウンロードとインストール確認に
 * 連れて行った挙げ句に何も起きない、という一番わかりにくい失敗になるため。
 * 逆に倒した場合は「アップデートを確認」を押しても最新と表示されるだけで、
 * 従来どおり手でサイドロードすれば済む。
 */
object AppVersion {

    /**
     * `"v1.37"` `"1.37"` `"1.37.1"` を `[1, 37]` `[1, 37, 1]` に分解する。
     * 数値以外が混ざる形 (`"v1.38-beta"` など) は解釈せず null を返す。
     */
    fun parse(raw: String?): List<Int>? {
        val trimmed = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(".")
        val numbers = parts.map { part ->
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            part.toIntOrNull() ?: return null
        }
        return numbers
    }

    /**
     * [candidateTag] (GitHub のタグ名) が [installedVersionName] より新しいか。
     *
     * 桁数が違う場合は足りない側を 0 とみなす (`1.37` と `1.37.0` は同じ)。
     * どちらかが解釈できなければ false。
     */
    fun isNewer(candidateTag: String?, installedVersionName: String?): Boolean {
        val candidate = parse(candidateTag) ?: return false
        val installed = parse(installedVersionName) ?: return false
        val length = maxOf(candidate.size, installed.size)
        for (i in 0 until length) {
            val c = candidate.getOrElse(i) { 0 }
            val n = installed.getOrElse(i) { 0 }
            if (c != n) return c > n
        }
        return false
    }

    /** 表示用に先頭の `v` を落とす。解釈できない形はそのまま返す。 */
    fun display(tagName: String?): String {
        val trimmed = tagName?.trim().orEmpty()
        return trimmed.removePrefix("v").removePrefix("V").ifEmpty { trimmed }
    }

    /**
     * リリースの添付ファイルからインストールすべき APK を選ぶ。
     *
     * CI が付ける名前 ([GitHubReleaseApi.APK_ASSET_NAME]) を第一候補にし、
     * 見つからなければ最初の `.apk` にフォールバックする。将来 CI の出力名が
     * 変わってもすぐ壊れないようにするための保険。
     */
    fun pickApkAsset(assets: List<GitHubRelease.Asset>?): GitHubRelease.Asset? {
        val usable = assets?.filter { !it.browserDownloadUrl.isNullOrBlank() } ?: return null
        return usable.firstOrNull { it.name == GitHubReleaseApi.APK_ASSET_NAME }
            ?: usable.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
    }
}
