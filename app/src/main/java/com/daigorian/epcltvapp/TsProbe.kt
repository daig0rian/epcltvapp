package com.daigorian.epcltvapp

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val TAG = "TsProbe"
private const val TS_PACKET_SIZE = 188
private const val SYNC_BYTE = 0x47.toByte()
private const val PAT_PID = 0x0000

/**
 * 収録済みTSのシーク用軽量プロービング実装。
 *
 * TsReadexDataSource はフィルタ通過後の出力に対して固定PIDを前提にしているが、
 * ここではフィルタを経由しない生の放送TSを直接HTTP Rangeで読み、PAT→PMTから
 * PCR PIDを特定してPCRの時刻を追跡する（VLCのmodules/demux/mpeg/ts.cのProbeStart/
 * ProbeEndに倣う）。全体を読むことはせず、ファイルの一部だけを読んで完結させる。
 *
 * シーク自体は都度の二分探索ではなく、head/tailの2点だけからLeanbackのシークUI
 * (PlaybackSeekDataProvider経由)をすぐ有効化し、シーク確定時に [refineSeekPoint] で
 * 1回だけ軽量プローブして補正する方式を使う。詳細はTsSeekDataProvider/
 * PlaybackVideoFragmentを参照。
 */
object TsProbe {

    /** PCRの時刻(ms)とそれが見つかった絶対バイト位置。 */
    data class TimePoint(val timeMs: Long, val byteOffset: Long)

    data class HeadProbeResult(val pcrPid: Int, val firstPcr: TimePoint)

    private const val HEAD_PROBE_INITIAL_BYTES = 256 * 1024L
    private const val HEAD_PROBE_MAX_BYTES = 3 * 1024 * 1024L
    private const val PCR_SCAN_INITIAL_BYTES = 256 * 1024L
    private const val PCR_SCAN_MAX_BYTES = 3 * 1024 * 1024L

    /**
     * 実際のファイルサイズをHEADリクエストで取得する。
     * EPGStationのDBメタデータ(VideoFile.size等)は録画がエラー等で途中終了した場合に
     * 実データとずれることがあるため使わず、常にサーバーへ問い合わせる。
     */
    fun fetchFileSize(url: String, client: OkHttpClient): Long? {
        val request = Request.Builder().url(url).head().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchFileSize failed: code=${response.code}")
                    return null
                }
                response.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchFileSize IOException: ${e.message}")
            null
        }
    }

    /** ファイル先頭を走査し、PCR PIDの特定と最初のPCR時刻取得を行う。 */
    fun probeHead(url: String, client: OkHttpClient): HeadProbeResult? {
        var scanBytes = HEAD_PROBE_INITIAL_BYTES
        while (scanBytes <= HEAD_PROBE_MAX_BYTES) {
            val bytes = readRange(url, client, 0, scanBytes) ?: return null
            val syncOffset = findSyncOffset(bytes)
            if (syncOffset >= 0) {
                val pmtPid = findPmtPid(bytes, syncOffset)
                if (pmtPid != null) {
                    val pcrPid = findPcrPidFromPmt(bytes, syncOffset, pmtPid)
                    if (pcrPid != null) {
                        val firstPcr = scanForFirstPcr(bytes, syncOffset, 0L, pcrPid)
                        if (firstPcr != null) {
                            return HeadProbeResult(pcrPid, firstPcr)
                        }
                    }
                }
            }
            if (bytes.size.toLong() < scanBytes) break // ファイル自体がscanBytesより小さい
            scanBytes *= 2
        }
        Log.w(TAG, "probeHead: failed to resolve PAT/PMT/PCR within ${HEAD_PROBE_MAX_BYTES} bytes")
        return null
    }

    /** ファイル末尾を走査し、既知のPCR PIDについて最後に見つかるPCRの時刻とバイト位置を返す。 */
    fun probeTail(url: String, client: OkHttpClient, fileSize: Long, pcrPid: Int): TimePoint? {
        var scanBytes = PCR_SCAN_INITIAL_BYTES
        while (scanBytes <= PCR_SCAN_MAX_BYTES) {
            val start = maxOf(0L, fileSize - scanBytes)
            val bytes = readRange(url, client, start, fileSize - start) ?: return null
            val syncOffset = findSyncOffset(bytes)
            if (syncOffset >= 0) {
                val result = scanForLastPcr(bytes, syncOffset, start, pcrPid)
                if (result != null) return result
            }
            if (start == 0L) break
            scanBytes *= 2
        }
        Log.w(TAG, "probeTail: failed to find PCR for pid=$pcrPid within ${PCR_SCAN_MAX_BYTES} bytes of EOF")
        return null
    }

    /**
     * 概算バイト位置(TsSeekDataProvider.estimateByteOffset等で求めた線形補間値)の近傍を
     * 単発プローブし、実際のPCR時刻とバイト位置を返す。シーク確定時に1回だけ呼ばれる想定
     * (二分探索のような繰り返しは行わない)。
     */
    fun refineSeekPoint(url: String, client: OkHttpClient, fileSize: Long, pcrPid: Int, guessByteOffset: Long): TimePoint? {
        val aligned = guessByteOffset.coerceIn(0, (fileSize - TS_PACKET_SIZE).coerceAtLeast(0))
        return readPcrNear(url, client, aligned, fileSize, pcrPid)
    }

    // ---- 内部実装 ----

    private fun readPcrNear(url: String, client: OkHttpClient, startByte: Long, fileSize: Long, pcrPid: Int): TimePoint? {
        var scanBytes = PCR_SCAN_INITIAL_BYTES
        while (scanBytes <= PCR_SCAN_MAX_BYTES) {
            val len = minOf(scanBytes, fileSize - startByte)
            if (len <= 0) return null
            val bytes = readRange(url, client, startByte, len) ?: return null
            val syncOffset = findSyncOffset(bytes)
            if (syncOffset >= 0) {
                val result = scanForFirstPcr(bytes, syncOffset, startByte, pcrPid)
                if (result != null) return result
            }
            if (startByte + scanBytes >= fileSize) break
            scanBytes *= 2
        }
        return null
    }

    private fun readRange(url: String, client: OkHttpClient, start: Long, length: Long): ByteArray? {
        if (length <= 0) return null
        val end = start + length - 1
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "readRange failed: code=${response.code} range=$start-$end")
                    return null
                }
                response.body?.bytes()
            }
        } catch (e: IOException) {
            Log.w(TAG, "readRange IOException: ${e.message}")
            null
        }
    }

    /** バッファ内で連続3パケット分(可能な限り)0x47が一致する位置をTSパケット境界として返す。 */
    private fun findSyncOffset(buf: ByteArray): Int {
        if (buf.size < TS_PACKET_SIZE * 2) return -1
        val maxStart = buf.size - TS_PACKET_SIZE * 2
        for (i in 0..maxStart) {
            if (buf[i] == SYNC_BYTE && buf[i + TS_PACKET_SIZE] == SYNC_BYTE) {
                val thirdOffset = i + TS_PACKET_SIZE * 2
                if (thirdOffset >= buf.size || buf[thirdOffset] == SYNC_BYTE) {
                    return i
                }
            }
        }
        return -1
    }

    private inline fun scanPackets(
        buf: ByteArray,
        syncOffset: Int,
        onPacket: (pid: Int, pusi: Boolean, packetStart: Int) -> Boolean,
    ) {
        var pos = syncOffset
        while (pos + TS_PACKET_SIZE <= buf.size) {
            if (buf[pos] == SYNC_BYTE) {
                val pid = ((buf[pos + 1].toInt() and 0x1F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
                val pusi = (buf[pos + 1].toInt() and 0x40) != 0
                if (!onPacket(pid, pusi, pos)) return
            }
            pos += TS_PACKET_SIZE
        }
    }

    /** アダプテーションフィールドを考慮したペイロード範囲。ペイロードが無ければnull。 */
    private fun payloadRange(buf: ByteArray, packetStart: Int): Pair<Int, Int>? {
        val afc = (buf[packetStart + 3].toInt() shr 4) and 0x03
        val hasPayload = (afc and 0x01) != 0
        if (!hasPayload) return null
        val afLen = if ((afc and 0x02) != 0) (buf[packetStart + 4].toInt() and 0xFF) + 1 else 0
        val payloadStart = packetStart + 4 + afLen
        val payloadEnd = packetStart + TS_PACKET_SIZE
        if (payloadStart >= payloadEnd) return null
        return payloadStart to (payloadEnd - payloadStart)
    }

    private fun findPmtPid(buf: ByteArray, syncOffset: Int): Int? {
        val assembler = SectionAssembler()
        var result: Int? = null
        scanPackets(buf, syncOffset) { pid, pusi, packetStart ->
            if (pid != PAT_PID) return@scanPackets true
            val range = payloadRange(buf, packetStart) ?: return@scanPackets true
            val section = assembler.feed(buf, range.first, range.second, pusi) ?: return@scanPackets true
            result = parsePatFirstProgramPid(section)
            result == null
        }
        return result
    }

    private fun findPcrPidFromPmt(buf: ByteArray, syncOffset: Int, pmtPid: Int): Int? {
        val assembler = SectionAssembler()
        var result: Int? = null
        scanPackets(buf, syncOffset) { pid, pusi, packetStart ->
            if (pid != pmtPid) return@scanPackets true
            val range = payloadRange(buf, packetStart) ?: return@scanPackets true
            val section = assembler.feed(buf, range.first, range.second, pusi) ?: return@scanPackets true
            result = parsePmtPcrPid(section)
            result == null
        }
        return result
    }

    private fun scanForFirstPcr(buf: ByteArray, syncOffset: Int, absoluteBase: Long, pcrPid: Int): TimePoint? {
        var result: TimePoint? = null
        scanPackets(buf, syncOffset) { pid, _, packetStart ->
            if (pid != pcrPid) return@scanPackets true
            val pcr90k = extractPcr(buf, packetStart) ?: return@scanPackets true
            result = TimePoint(pcr90k / 90L, absoluteBase + packetStart)
            false
        }
        return result
    }

    private fun scanForLastPcr(buf: ByteArray, syncOffset: Int, absoluteBase: Long, pcrPid: Int): TimePoint? {
        var result: TimePoint? = null
        scanPackets(buf, syncOffset) { pid, _, packetStart ->
            if (pid == pcrPid) {
                val pcr90k = extractPcr(buf, packetStart)
                if (pcr90k != null) result = TimePoint(pcr90k / 90L, absoluteBase + packetStart)
            }
            true // 末尾に一番近いものを採用するため最後まで走査する
        }
        return result
    }

    /** adaptation_field中のPCR(90kHzベース、拡張分は誤差500ms許容の探索には不要なため無視)。 */
    private fun extractPcr(buf: ByteArray, packetStart: Int): Long? {
        val afc = (buf[packetStart + 3].toInt() shr 4) and 0x03
        if ((afc and 0x02) == 0) return null
        val afLen = buf[packetStart + 4].toInt() and 0xFF
        if (afLen < 7) return null
        val flags = buf[packetStart + 5].toInt() and 0xFF
        if ((flags and 0x10) == 0) return null // PCR_flag
        val b0 = buf[packetStart + 6].toLong() and 0xFF
        val b1 = buf[packetStart + 7].toLong() and 0xFF
        val b2 = buf[packetStart + 8].toLong() and 0xFF
        val b3 = buf[packetStart + 9].toLong() and 0xFF
        val b4 = buf[packetStart + 10].toLong() and 0xFF
        return (b0 shl 25) or (b1 shl 17) or (b2 shl 9) or (b3 shl 1) or (b4 ushr 7)
    }

    private fun parsePatFirstProgramPid(section: ByteArray): Int? {
        if (section.size < 8) return null
        if ((section[0].toInt() and 0xFF) != 0x00) return null
        var i = 8
        while (i + 4 <= section.size - 4) { // 末尾4byteはCRC32
            val programNumber = ((section[i].toInt() and 0xFF) shl 8) or (section[i + 1].toInt() and 0xFF)
            val pid = ((section[i + 2].toInt() and 0x1F) shl 8) or (section[i + 3].toInt() and 0xFF)
            if (programNumber != 0) return pid
            i += 4
        }
        return null
    }

    private fun parsePmtPcrPid(section: ByteArray): Int? {
        if (section.size < 10) return null
        if ((section[0].toInt() and 0xFF) != 0x02) return null
        val pcrPid = ((section[8].toInt() and 0x1F) shl 8) or (section[9].toInt() and 0xFF)
        return if (pcrPid in 0..0x1FFE) pcrPid else null
    }

    /** PAT/PMTのセクションが複数TSパケットに跨る場合の再構成。 */
    private class SectionAssembler {
        private var buf = ByteArray(0)
        private var expectedLen = -1

        fun feed(src: ByteArray, offset: Int, length: Int, pusi: Boolean): ByteArray? {
            var pos = offset
            val end = offset + length
            if (pusi) {
                if (pos >= end) return null
                val pointerField = src[pos].toInt() and 0xFF
                pos += 1 + pointerField
                if (pos >= end) {
                    buf = ByteArray(0)
                    expectedLen = -1
                    return null
                }
                buf = src.copyOfRange(pos, end)
                if (buf.size < 3) {
                    expectedLen = -1
                    return null
                }
                val sectionLength = ((buf[1].toInt() and 0x0F) shl 8) or (buf[2].toInt() and 0xFF)
                expectedLen = 3 + sectionLength
            } else {
                if (expectedLen < 0) return null
                buf += src.copyOfRange(pos, end)
            }
            return if (expectedLen in 1..buf.size) buf.copyOf(expectedLen) else null
        }
    }
}
