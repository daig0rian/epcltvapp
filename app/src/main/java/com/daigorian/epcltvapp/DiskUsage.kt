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
     * 録画に使える容量。「使用済み + 空き」。
     *
     * サーバーは全体の容量も返すが、そちらは使わない。全体にはファイルシステムが
     * 管理者用に取り置く領域が含まれ、それは使用済みにも空きにも入らない（検証に使ったサーバーでは
     * 全体の約4%）。全体を分母にすると、空きが 0 になっても 100% に届かない。
     * 利用者が知りたいのは「あとどれだけで埋まるか」なので、空きが 0 のときにちょうど 100% になる
     * ほうを選んでいる。df の Use% と同じ数え方。
     */
    val capacityBytes: Long get() = usedBytes + availableBytes

    /** 使用率（0.0〜1.0）。分母は [capacityBytes]。 */
    val usedRatio: Float
        get() {
            val capacity = capacityBytes
            if (capacity <= 0L) return 0f
            return (usedBytes.toDouble() / capacity).toFloat().coerceIn(0f, 1f)
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
     * ラベルのうち名前より後ろの部分。`Used 282GB/435GB · 65%`、満杯なら `Full 435GB/435GB`。
     *
     * 使用量は容量との分数で書く。使用量だけだと、ほとんど使っていない保存先の大きさが読み取れない。
     *
     * 名前は幅に収まらなければ切り詰めるが、こちらは切らない。そのため分けて返す。
     */
    val labelBody: String
        get() {
            val amount = formatAmount(usedBytes, capacityBytes)
            return if (isFull) "Full $amount" else "Used $amount · $usedPercent%"
        }

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
         * 使用量と容量を `282GB/435GB` の形で書く。
         *
         * 単位と小数の桁は容量に合わせて、使用量も同じにする。別々に選ぶと `3KB/500GB` のように
         * 食い違い、比べにくい。容量は有効数字3桁ほどになるようにする（`435GB`、`45.3GB`、`1.73TB`）。
         *
         * 1024 で割って GB / TB と書く。EPGStation の Web UI と同じ数え方なので、数字が揃う。
         */
        fun formatAmount(usedBytes: Long, capacityBytes: Long): String {
            var capacity = capacityBytes.coerceAtLeast(0L).toDouble()
            var used = usedBytes.coerceAtLeast(0L).toDouble()
            var unit = 0
            // 999.5 以上は丸めると4桁になるので、次の単位へ上げる
            while (capacity >= 999.5 && unit < UNITS.lastIndex) {
                capacity /= 1024
                used /= 1024
                unit++
            }
            val decimals = when {
                unit == 0 -> 0
                capacity < 9.995 -> 2
                capacity < 99.95 -> 1
                else -> 0
            }
            val number = "%.${decimals}f"
            return String.format(Locale.US, "$number%s/$number%s", used, UNITS[unit], capacity, UNITS[unit])
        }
    }

    /** 棒にできる値か。応答に値が欠けていると 0 や負になる。 */
    private val isValid: Boolean
        get() = usedBytes >= 0L && availableBytes >= 0L && capacityBytes > 0L
}
