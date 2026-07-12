package com.daigorian.epcltvapp

import java.net.HttpURLConnection
import java.net.URL

object CustomUrlAction {
    /** 指定したURLにGETリクエストを送る。ネットワークI/Oのため呼び出し側でバックグラウンドスレッドから呼ぶこと。 */
    fun get(urlString: String) {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.connect()
            connection.responseCode // レスポンスを読み切ってリクエストを確定させる
        } finally {
            connection.disconnect()
        }
    }
}
