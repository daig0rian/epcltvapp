package com.daigorian.epcltvapp

/**
 * 接続先の EPGStation に、このアプリがつないでよいかを決める。
 *
 * | サーバーの応答 | 判定 |
 * |---|---|
 * | `/api/version` が返る（メジャー番号が2以上、または番号が読めない） | 接続する |
 * | `/api/version` が 404 で、`/api/storage` が 200 | v1。接続しない |
 * | それ以外（応答が無い、EPGStation ではない） | 接続失敗 |
 *
 * `/api/storage`（単数形）は v1 にだけある（v2・v3 は `/api/storages`）。この問い合わせは
 * `/api/version` が 404 だったときにだけ送る。
 *
 * 番号が読めないものも接続するのは、v2 以降の番号の書き方が変わっても、つながる相手を
 * 締め出さないため。
 *
 * Android に依存しないので、単体テストから直接呼べる。
 */
object ServerCompatibility {

    /** `/api/version` の応答を見て、次にすること。 */
    sealed class AfterVersion {
        /** 接続する。[version] は応答にあった番号（無ければ null）。 */
        data class Connect(val version: String?) : AfterVersion()

        /** v1 かもしれない。`/api/storage` を確かめる。 */
        object CheckV1 : AfterVersion()

        /** 接続できない。 */
        object Fail : AfterVersion()
    }

    /**
     * @param httpCode `/api/version` の HTTP ステータス
     * @param hasBody 本文を読めたか（Retrofit の `response.body() != null`）
     * @param version 本文にあった番号
     */
    fun afterVersion(httpCode: Int, hasBody: Boolean, version: String?): AfterVersion = when {
        httpCode in 200..299 && hasBody ->
            if (isSupported(version)) AfterVersion.Connect(version) else AfterVersion.Fail
        httpCode == 404 -> AfterVersion.CheckV1
        else -> AfterVersion.Fail
    }

    /** `/api/storage` の HTTP ステータスから、v1 かを決める。 */
    fun isV1(storageHttpCode: Int): Boolean = storageHttpCode == 200

    /** 接続してよい番号か。メジャー番号が2以上、または番号が読めなければ接続する。 */
    fun isSupported(version: String?): Boolean {
        val major = majorOf(version) ?: return true
        return major >= MIN_SUPPORTED_MAJOR
    }

    /** `"2.10.0"` → 2、`"3.0.0-beta4"` → 3。先頭が数字でなければ null。 */
    fun majorOf(version: String?): Int? =
        version?.trim()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private const val MIN_SUPPORTED_MAJOR = 2
}
