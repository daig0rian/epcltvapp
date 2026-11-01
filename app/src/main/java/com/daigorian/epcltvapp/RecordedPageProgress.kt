package com.daigorian.epcltvapp

/** Offsets count raw API records, before local filtering or ID deduplication. */
object RecordedPageProgress {
    fun requestLimit(loadedCount: Int, pageSize: Long): Long =
        if (loadedCount < pageSize) pageSize - loadedCount else pageSize

    fun nextOffset(offset: Long, returnedCount: Int): Long = offset + returnedCount.coerceAtLeast(0)

    fun isUnexpectedEmpty(offset: Long, returnedCount: Int, total: Int): Boolean =
        returnedCount == 0 && offset < total
}
