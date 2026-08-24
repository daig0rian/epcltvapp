package com.daigorian.epcltvapp.githubcaller

import com.google.gson.annotations.SerializedName

/**
 * GitHub Releases API の `GET /repos/{owner}/{repo}/releases/latest` の応答。
 *
 * 実際の応答は数十のフィールドを持つが、必要なものだけを宣言している
 * (Gson は宣言のないフィールドを黙って捨てる)。
 */
data class GitHubRelease(
    /** タグ名。このリポジトリでは `v1.37` の形。 */
    @SerializedName("tag_name") val tagName: String?,
    /** リリースの表示名。未設定なら null。 */
    @SerializedName("name") val name: String?,
    /** リリースノート本文 (Markdown)。 */
    @SerializedName("body") val body: String?,
    /** 添付ファイル。APK はこの中から探す。 */
    @SerializedName("assets") val assets: List<Asset>?
) {
    data class Asset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?,
        /** バイト数。進捗表示の分母に使う (応答ヘッダが当てにならない場合の保険)。 */
        @SerializedName("size") val size: Long?
    )
}
