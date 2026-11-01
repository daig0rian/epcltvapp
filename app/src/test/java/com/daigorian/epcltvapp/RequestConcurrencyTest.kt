package com.daigorian.epcltvapp

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestConcurrencyTest {

    @Test
    fun `既定値と選択肢は仕様どおり`() {
        assertEquals(5, RequestConcurrency.DEFAULT)
        assertEquals(listOf(1, 2, 3, 5, 8, 13), RequestConcurrency.CHOICES)
    }

    @Test
    fun `選択肢の設定文字列はそのまま採用する`() {
        RequestConcurrency.CHOICES.forEach { value ->
            assertEquals(value, RequestConcurrency.fromPreference(value.toString()))
        }
    }

    @Test
    fun `設定文字列の前後の空白は無視する`() {
        assertEquals(5, RequestConcurrency.fromPreference(" 5 "))
    }

    @Test
    fun `不正または未設定の設定文字列は既定値へ戻す`() {
        val invalidValues = listOf(
            null,
            "",
            " ",
            "-2",
            "0",
            "4",
            "6",
            "12",
            "21",
            "2.0",
            "many",
            "default"
        )

        invalidValues.forEach { value ->
            assertEquals(
                "value=$value",
                RequestConcurrency.DEFAULT,
                RequestConcurrency.fromPreference(value)
            )
        }
    }

    @Test
    fun `選択肢外のIntは既定値へ戻す`() {
        RequestConcurrency.CHOICES.forEach { value ->
            assertEquals(value, RequestConcurrency.fromValue(value))
        }

        listOf(Int.MIN_VALUE, -2, 0, 4, 6, 12, 21, Int.MAX_VALUE).forEach { value ->
            assertEquals(
                "value=$value",
                RequestConcurrency.DEFAULT,
                RequestConcurrency.fromValue(value)
            )
        }
    }
}
