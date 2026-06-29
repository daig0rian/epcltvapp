package com.daigorian.epcltvapp

import android.graphics.Bitmap

class CaptionImage(
    @JvmField val bitmap: Bitmap,
    @JvmField val x: Int,
    @JvmField val y: Int,
    @JvmField val durationMs: Long,
)
