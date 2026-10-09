package com.daigorian.epcltvapp.epgstationcaller

// 録画の保存先のディスク使用状況。値はすべてバイト数。v1 は保存先が1つだけで、名前は返さない。
data class StorageInfoV1(
    val free: Long = 0,
    val used: Long = 0,
    val total: Long = 0
)
