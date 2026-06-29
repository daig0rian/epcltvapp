package com.daigorian.epcltvapp

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

private const val TAG = "TsReadexDataSource"
private const val TS_PACKET_SIZE = 188
private const val BATCH_PACKETS = 64
private const val BATCH_SIZE = TS_PACKET_SIZE * BATCH_PACKETS

private const val CAPTION_PID = 0x0130
private const val SUPERIMPOSE_PID = 0x0138
private const val AUDIO_PID_MAIN = 0x0110
private const val AUDIO_PID_SUB  = 0x0111
private const val AUDIO_PTS_INCREMENT = 3840L

internal fun interface PesCallback {
    fun onPes(ptsMs: Long, pesPayload: ByteArray)
}

@UnstableApi
internal class TsReadexDataSource(private val upstream: DataSource) : DataSource {

    private var filterHandle: Long = 0

    private val partialPacket = ByteArray(TS_PACKET_SIZE)
    private var partialLen = 0

    private var outputBytes: ByteArray = EMPTY
    private var outputPos = 0

    private val readBuf = ByteArray(BATCH_SIZE)

    var captionPesListener: PesCallback? = null
    var superimposePesListener: PesCallback? = null

    private val captionExtractor = PesExtractor(CAPTION_PID) { captionPesListener }
    private val superimposeExtractor = PesExtractor(SUPERIMPOSE_PID) { superimposePesListener }

    private var lastAudioPtsMain = -1L
    private var lastAudioPtsSub  = -1L
    private var ptsInjectionCount = 0

    companion object {
        private val EMPTY = ByteArray(0)
    }

    override fun open(dataSpec: DataSpec): Long {
        filterHandle = TsReadexFilter.create(
            programNumberOrIndex = -1,
            audio1Mode = 1 + 4 + 8,
            audio2Mode = 1 + 4,
            captionMode = 1,
            superimposeMode = 1,
        )
        Log.d(TAG, "open: filter handle=$filterHandle uri=${dataSpec.uri}")
        partialLen = 0
        outputBytes = EMPTY
        outputPos = 0
        captionExtractor.reset()
        superimposeExtractor.reset()
        lastAudioPtsMain = -1L
        lastAudioPtsSub = -1L
        ptsInjectionCount = 0
        upstream.open(dataSpec)
        // tsreadex は先頭からの逐次処理を前提とするため、ExoPlayer に
        // ストリーム長不明と伝えて TsDurationReader のシーク試行を抑止する
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (outputPos < outputBytes.size) {
            val toCopy = minOf(outputBytes.size - outputPos, length)
            System.arraycopy(outputBytes, outputPos, target, offset, toCopy)
            outputPos += toCopy
            return toCopy
        }

        while (true) {
            val upstreamRead = upstream.read(readBuf, 0, readBuf.size)
            if (upstreamRead == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            if (upstreamRead <= 0) continue

            val available = partialLen + upstreamRead
            val combined: ByteArray
            if (partialLen > 0) {
                combined = ByteArray(available)
                System.arraycopy(partialPacket, 0, combined, 0, partialLen)
                System.arraycopy(readBuf, 0, combined, partialLen, upstreamRead)
            } else {
                combined = readBuf.copyOf(upstreamRead)
            }

            val fullPackets = available / TS_PACKET_SIZE
            val leftover = available % TS_PACKET_SIZE

            if (fullPackets == 0) {
                System.arraycopy(combined, 0, partialPacket, 0, leftover)
                partialLen = leftover
                continue
            }

            partialLen = leftover
            if (leftover > 0) {
                System.arraycopy(combined, fullPackets * TS_PACKET_SIZE, partialPacket, 0, leftover)
            }

            val processed = TsReadexFilter.processPackets(filterHandle, combined, fullPackets * TS_PACKET_SIZE)
            if (processed.isNotEmpty()) {
                patchAudioPesPts(processed)
                if (captionPesListener != null) captionExtractor.extract(processed)
                if (superimposePesListener != null) superimposeExtractor.extract(processed)
                outputBytes = processed
                outputPos = 0
                val toCopy = minOf(processed.size, length)
                System.arraycopy(processed, 0, target, offset, toCopy)
                outputPos = toCopy
                return toCopy
            }
        }
    }

    private fun patchAudioPesPts(chunk: ByteArray) {
        var i = 0
        while (i + TS_PACKET_SIZE <= chunk.size) {
            if (chunk[i] != 0x47.toByte()) { i += TS_PACKET_SIZE; continue }

            val pid = ((chunk[i + 1].toInt() and 0x1F) shl 8) or (chunk[i + 2].toInt() and 0xFF)
            if (pid != AUDIO_PID_MAIN && pid != AUDIO_PID_SUB) { i += TS_PACKET_SIZE; continue }

            val pusi = (chunk[i + 1].toInt() and 0x40) != 0
            if (!pusi) { i += TS_PACKET_SIZE; continue }

            val afc = (chunk[i + 3].toInt() shr 4) and 0x03
            if ((afc and 0x01) == 0) { i += TS_PACKET_SIZE; continue }
            val afLen = if ((afc and 0x02) != 0) (chunk[i + 4].toInt() and 0xFF) + 1 else 0
            val pStart = i + 4 + afLen
            val avail = i + TS_PACKET_SIZE - pStart
            if (avail < 14) { i += TS_PACKET_SIZE; continue }

            if (chunk[pStart] != 0x00.toByte() ||
                chunk[pStart + 1] != 0x00.toByte() ||
                chunk[pStart + 2] != 0x01.toByte()) { i += TS_PACKET_SIZE; continue }

            val flags2 = chunk[pStart + 7].toInt() and 0xFF
            val ptsDtsFlags = (flags2 shr 6) and 0x03
            val headerDataLen = chunk[pStart + 8].toInt() and 0xFF

            if (ptsDtsFlags and 0x02 != 0) {
                val p = pStart + 9
                val pts = ((chunk[p].toLong() and 0x0E) shl 29) or
                          ((chunk[p + 1].toLong() and 0xFF) shl 22) or
                          ((chunk[p + 2].toLong() and 0xFE) shl 14) or
                          ((chunk[p + 3].toLong() and 0xFF) shl 7) or
                          ((chunk[p + 4].toLong() and 0xFE) ushr 1)
                if (pid == AUDIO_PID_MAIN) lastAudioPtsMain = pts else lastAudioPtsSub = pts
            } else if (ptsDtsFlags == 0 && headerDataLen >= 5) {
                val lastPts = if (pid == AUDIO_PID_MAIN) lastAudioPtsMain else lastAudioPtsSub
                if (lastPts >= 0) {
                    val inject = lastPts + AUDIO_PTS_INCREMENT
                    chunk[pStart + 7] = (flags2 or 0x80).toByte()
                    val p = pStart + 9
                    chunk[p]     = (0x21 or ((inject ushr 29).toInt() and 0x0E)).toByte()
                    chunk[p + 1] = ((inject ushr 22).toInt() and 0xFF).toByte()
                    chunk[p + 2] = (0x01 or ((inject ushr 14).toInt() and 0xFE)).toByte()
                    chunk[p + 3] = ((inject ushr 7).toInt() and 0xFF).toByte()
                    chunk[p + 4] = (0x01 or ((inject shl 1).toInt() and 0xFE)).toByte()
                    if (pid == AUDIO_PID_MAIN) lastAudioPtsMain = inject else lastAudioPtsSub = inject
                    ptsInjectionCount++
                    if (ptsInjectionCount <= 3 || ptsInjectionCount % 100 == 0) {
                        Log.i(TAG, "PTS injected #$ptsInjectionCount PID=0x${String.format("%04X", pid)} pts=${inject / 90}ms")
                    }
                }
            } else if (ptsDtsFlags == 0) {
                Log.w(TAG, "PTS missing PID=0x${String.format("%04X", pid)} headerDataLen=$headerDataLen < 5, cannot inject")
            }
            i += TS_PACKET_SIZE
        }
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (filterHandle != 0L) {
                TsReadexFilter.destroy(filterHandle)
                filterHandle = 0
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        var captionPesListener: PesCallback? = null
        var superimposePesListener: PesCallback? = null

        override fun createDataSource(): DataSource =
            TsReadexDataSource(upstreamFactory.createDataSource()).also {
                it.captionPesListener = captionPesListener
                it.superimposePesListener = superimposePesListener
            }
    }
}

private class PesExtractor(
    private val pid: Int,
    private val listenerProvider: () -> PesCallback?,
) {
    private val buf = ByteArray(65536)
    private var len = 0
    private var pts = -1L

    fun reset() {
        len = 0
        pts = -1L
    }

    fun extract(chunk: ByteArray) {
        var i = 0
        while (i + TS_PACKET_SIZE <= chunk.size) {
            if (chunk[i] != 0x47.toByte()) { i += TS_PACKET_SIZE; continue }

            val curPid = ((chunk[i + 1].toInt() and 0x1F) shl 8) or (chunk[i + 2].toInt() and 0xFF)
            if (curPid != pid) { i += TS_PACKET_SIZE; continue }

            val pusi = (chunk[i + 1].toInt() and 0x40) != 0
            val afc = (chunk[i + 3].toInt() shr 4) and 0x03
            val hasPayload = (afc and 0x01) != 0
            if (!hasPayload) { i += TS_PACKET_SIZE; continue }

            val afLen = if ((afc and 0x02) != 0) (chunk[i + 4].toInt() and 0xFF) + 1 else 0
            val payloadStart = i + 4 + afLen
            val payloadEnd = i + TS_PACKET_SIZE
            if (payloadStart >= payloadEnd) { i += TS_PACKET_SIZE; continue }

            if (pusi) {
                dispatch()
                parsePesStart(chunk, payloadStart, payloadEnd)
            } else {
                if (len > 0) appendPayload(chunk, payloadStart, payloadEnd)
            }
            i += TS_PACKET_SIZE
        }
    }

    private fun parsePesStart(chunk: ByteArray, payloadStart: Int, payloadEnd: Int) {
        val avail = payloadEnd - payloadStart
        if (avail < 9) return

        if (chunk[payloadStart] != 0x00.toByte() ||
            chunk[payloadStart + 1] != 0x00.toByte() ||
            chunk[payloadStart + 2] != 0x01.toByte()) return

        val flagsByte = chunk[payloadStart + 7].toInt() and 0xFF
        val ptsDtsFlags = (flagsByte shr 6) and 0x03
        val headerDataLength = chunk[payloadStart + 8].toInt() and 0xFF

        val pesPayloadOffset = 9 + headerDataLength
        if (pesPayloadOffset > avail) return

        pts = if ((ptsDtsFlags and 0x02) != 0 && avail >= 14) {
            val p = payloadStart + 9
            val pts90k = ((chunk[p].toLong() and 0x0E) shl 29) or
                         ((chunk[p + 1].toLong() and 0xFF) shl 22) or
                         ((chunk[p + 2].toLong() and 0xFE) shl 14) or
                         ((chunk[p + 3].toLong() and 0xFF) shl 7) or
                         ((chunk[p + 4].toLong() and 0xFE) ushr 1)
            pts90k / 90L
        } else {
            -1L
        }

        val dataStart = payloadStart + pesPayloadOffset
        val dataLen = payloadEnd - dataStart
        if (dataLen > 0 && dataLen <= buf.size) {
            System.arraycopy(chunk, dataStart, buf, 0, dataLen)
            len = dataLen
        }
    }

    private fun appendPayload(chunk: ByteArray, from: Int, to: Int) {
        val dataLen = to - from
        if (len + dataLen > buf.size) return
        System.arraycopy(chunk, from, buf, len, dataLen)
        len += dataLen
    }

    private fun dispatch() {
        if (len <= 0 || pts < 0) {
            len = 0
            return
        }
        if (buf[0] != 0x80.toByte()) {
            len = 0
            return
        }
        val payload = buf.copyOf(len)
        listenerProvider()?.onPes(pts, payload)
        len = 0
    }
}
