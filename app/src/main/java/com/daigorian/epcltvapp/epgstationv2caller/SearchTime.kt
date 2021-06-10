package com.daigorian.epcltvapp.epgstationv2caller
//時刻範囲指定オプション
data class SearchTime(
    val start	:Long?, //   開始時刻 1 - 23, 時刻予約の場合は 0 時を 0 とした 0 ~ (60 * 50 * 24) - 1 秒までの開始時刻を指定する
    val range	:Long?, //開始時刻からの時刻範囲(時) 1 - 23, 時刻予約の場合は秒で時間の長さを指定する 1 ~ 60 * 50 * 24 秒
    val week	:Long?, //曜日指定 0x01, 0x02, 0x04, 0x08, 0x10, 0x20 ,0x40 が日〜土に対応するので and 演算で曜日を指定する
)
