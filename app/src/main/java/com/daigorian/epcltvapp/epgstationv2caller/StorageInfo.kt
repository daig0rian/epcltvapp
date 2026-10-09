package com.daigorian.epcltvapp.epgstationv2caller

// 録画の保存先（config.yml の recorded）ごとのディスク使用状況。値はすべてバイト数。
data class StorageInfo(
    val items: List<StorageItem>?
)

data class StorageItem(
    val name: String?, // config.yml で付けた保存先名
    val available: Long = 0,
    val used: Long = 0,
    val total: Long = 0
)
