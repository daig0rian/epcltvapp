package com.daigorian.epcltvapp

import com.daigorian.epcltvapp.epgstationcaller.StorageInfoV1
import com.daigorian.epcltvapp.epgstationv2caller.StorageInfo
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 録画の保存先1つぶんのディスク使用状況。ホームのタイトル文字の下に出す棒1本に当たる
 * （[DiskUsageBarView]）。
 *
 * Android に依存しないので、単体テストから直接呼べる。
 */
data class DiskUsage(
    /** config.yml で付けた保存先名。EPGStation v1 は名前を返さないので null。 */
    val name: String?,
    val usedBytes: Long,
    val availableBytes: Long,
) {
    /**
     * 使用率（0.0〜1.0）。分母は「使用済み + 空き」。
     *
     * サーバーは全体の容量も返すが、そちらを分母にはしない。全体にはファイルシステムが
     * 管理者用に取り置く領域が含まれ、それは使用済みにも空きにも入らない（検証に使ったサーバーでは
     * 全体の約4%）。全体を分母にすると、空きが 0 になっても 100% に届かない。
     * df の Use% と同じ数え方にしている。
     */
    val usedRatio: Float
        get() {
            val usable = usedBytes + availableBytes
            if (usable <= 0L) return 0f
            return (usedBytes.toDouble() / usable).toFloat().coerceIn(0f, 1f)
        }

    /** 使用率を四捨五入した整数（0〜100）。 */
    val usedPercent: Int get() = (usedRatio * 100).roundToInt()

    /** 満杯として扱うか。表示が 100% になるときを満杯とする。 */
    val isFull: Boolean get() = usedPercent >= 100

    /**
     * ラベルの先頭に出す名前。
     *
     * 保存先が1つだけなら、どの保存先かを示す必要がないので [DEFAULT_NAME] にする。
     * 複数あるときは保存先名で見分ける。
     *
     * @param entryCount 一緒に並べる保存先の数
     */
    fun labelName(entryCount: Int): String =
        if (entryCount > 1 && !name.isNullOrBlank()) name else DEFAULT_NAME

    /**
     * ラベルのうち名前より後ろの部分。`Used 282 GB · 64%`、満杯なら `Full`。
     *
     * 名前は幅に収まらなければ切り詰めるが、こちらは切らない。そのため分けて返す。
     */
    val labelBody: String
        get() = if (isFull) "Full" else "Used ${formatSize(usedBytes)} · $usedPercent%"

    companion object {
        /** 保存先名を出さないときに、名前の位置へ出す語。 */
        const val DEFAULT_NAME = "Disk"

        /**
         * 一度に出す保存先の数の上限。
         *
         * 棒はタイトル文字の下へ縦に積む。最初の行のカードにかからずに積めるのが4本まで。
         */
        const val MAX_ENTRIES = 4

        private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

        /** EPGStation v2 の応答から、出す順（config.yml の順）に並べて返す。 */
        fun fromV2(info: StorageInfo?): List<DiskUsage> =
            info?.items.orEmpty()
                .map { DiskUsage(it.name, it.used, it.available) }
                .filter { it.isValid }
                .take(MAX_ENTRIES)

        /** EPGStation v1 の応答から。v1 の保存先は1つだけ。 */
        fun fromV1(info: StorageInfoV1?): List<DiskUsage> =
            listOfNotNull(info?.let { DiskUsage(null, it.used, it.free) })
                .filter { it.isValid }

        /**
         * バイト数を有効数字3桁ほどの短い表記にする（`282 GB`、`45.3 GB`、`1.82 TB`）。
         *
         * 1024 で割って GB / TB と書く。EPGStation の Web UI と同じ数え方なので、数字が揃う。
         */
        fun formatSize(bytes: Long): String {
            var value = bytes.coerceAtLeast(0L).toDouble()
            var unit = 0
            // 999.5 以上は丸めると4桁になるので、次の単位へ上げる
            while (value >= 999.5 && unit < UNITS.lastIndex) {
                value /= 1024
                unit++
            }
            val decimals = when {
                unit == 0 -> 0
                value < 9.995 -> 2
                value < 99.95 -> 1
                else -> 0
            }
            return String.format(Locale.US, "%.${decimals}f %s", value, UNITS[unit])
        }
    }

    /** 棒にできる値か。応答に値が欠けていると 0 や負になる。 */
    private val isValid: Boolean
        get() = usedBytes >= 0L && availableBytes >= 0L && usedBytes + availableBytes > 0L
}
