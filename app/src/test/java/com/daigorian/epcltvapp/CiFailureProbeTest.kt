package com.daigorian.epcltvapp

import org.junit.Assert.fail
import org.junit.Test

/**
 * **CI の失敗経路を確認するためだけの一時的なテスト。この PR はマージしない。**
 *
 * 単体テストが落ちたときに、PR のチェックが赤くなり、テストレポートが artifact として
 * 保存されるかを確かめる。確認が終わったら PR ごと閉じてブランチを削除する。
 */
class CiFailureProbeTest {

    @Test
    fun わざと失敗してCIが赤くなることを確かめる() {
        fail("CI の失敗経路を確認するための意図的な失敗です")
    }
}
