package com.daigorian.epcltvapp

internal object AribCaptionFilter {

    const val TYPE_CAPTION = 0
    const val TYPE_SUPERIMPOSE = 1

    init {
        System.loadLibrary("tsreadex")
    }

    fun create(frameWidth: Int, frameHeight: Int, captionType: Int = TYPE_CAPTION): Long =
        nativeCreate(frameWidth, frameHeight, captionType)

    fun setFrameSize(handle: Long, w: Int, h: Int) =
        nativeSetFrameSize(handle, w, h)

    fun decode(handle: Long, ptsMs: Long, pesPayload: ByteArray, offset: Int, len: Int): Boolean =
        nativeDecode(handle, ptsMs, pesPayload, offset, len)

    fun render(handle: Long, ptsMs: Long): Array<CaptionImage> =
        nativeRender(handle, ptsMs)

    fun flush(handle: Long) = nativeFlush(handle)

    fun destroy(handle: Long) = nativeDestroy(handle)

    @JvmStatic private external fun nativeCreate(frameWidth: Int, frameHeight: Int, captionType: Int): Long
    @JvmStatic private external fun nativeSetFrameSize(handle: Long, w: Int, h: Int)
    @JvmStatic private external fun nativeDecode(handle: Long, ptsMs: Long, pesPayload: ByteArray, offset: Int, len: Int): Boolean
    @JvmStatic private external fun nativeRender(handle: Long, ptsMs: Long): Array<CaptionImage>
    @JvmStatic private external fun nativeFlush(handle: Long)
    @JvmStatic private external fun nativeDestroy(handle: Long)
}
