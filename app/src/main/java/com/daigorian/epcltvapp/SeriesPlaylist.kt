package com.daigorian.epcltvapp

import android.util.Log
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.GetRecordedResponse
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem
import com.daigorian.epcltvapp.epgstationv2caller.Records
import com.daigorian.epcltvapp.epgstationv2caller.VideoFile
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.Normalizer

/**
 * 同じシリーズの録画を録画順に並べたもの。連続再生と前/次トラックの移動先を決めるのに使う。
 *
 * ## 前後の決め方
 *
 * 話数の表記は一切見ない。[SeriesTitleExtractor] で取り出したシリーズ名で録画を検索し、
 * **録画開始時刻の順に並べた隣**を前後の回とする。「#2 の次は #3」のような番号の解釈は
 * しない——話数の書き方は番組・放送局ごとに揺れが大きく、番号を持たない番組も多い。
 * 録画された順は必ず全ての録画が持っていて、実際の放送順とも食い違いにくい。
 *
 * ## 番組名での絞り込み
 *
 * EPGStation のキーワード検索は番組名と番組概要の**どちらか**に当たれば拾う
 * (`RecordedDB.ts`: `halfWidthName ... OR halfWidthDescription ...`)。概要にシリーズ名が
 * 出てくるだけの無関係な番組——ゲスト紹介や次回予告で名前が挙がった番組など——へ自動再生で
 * 飛んでしまうため、番組名に当たったものだけをここで残す。
 *
 * 詳細画面の関連動画一覧はこの絞り込みをしていない。あちらは人が見て選ぶ一覧なので、
 * 拾いすぎても実害がないため。
 */
class SeriesPlaylist private constructor(
    /** 録画開始時刻の昇順。エピソード一覧の並びもこれをそのまま使う。 */
    val entries: List<SeriesEntry>,
    /**
     * このシリーズを集めるのに使ったシリーズ名 ([SeriesTitleExtractor] が取り出したもの)。
     * エピソード一覧の見出しに出す。
     */
    val seriesTitle: String,
) {
    /** [entries] の中で今見ている回が何番目か。居なければ -1。 */
    fun indexOf(currentId: Long): Int = entries.indexOfFirst { it.id == currentId }

    /**
     * 今見ている回の次に録画されたもの。無ければ null。
     *
     * 今見ている回が一覧に居ない場合(検索できる範囲より古い回を見ている場合など)も、
     * どれが次か決められないので null を返す。
     */
    fun next(currentId: Long): SeriesEntry? {
        val index = indexOf(currentId)
        if (index < 0) return null
        return entries.getOrNull(index + 1)
    }

    companion object {
        private const val TAG = "SeriesPlaylist"

        /**
         * 1回の検索で取りに行く件数。ほとんどのシリーズはこの1回で収まる。
         * 収まらなかったときだけ [MAX_PAGES] まで続きを取りに行く。
         */
        private const val PAGE_SIZE = 100L

        /**
         * 続きを取りに行く回数の上限。長寿番組の古い回を見ているときなど、今見ている回に
         * たどり着けないまま検索を繰り返すのを止めるための歯止め。
         */
        private const val MAX_PAGES = 5

        /**
         * 今見ている番組と同じシリーズの録画を集める。
         *
         * 結果はメインスレッドで [onLoaded] に返る(Retrofit の enqueue と同じ)。
         * シリーズ名が取り出せない・APIが未初期化・通信に失敗した場合は null を返す。
         */
        fun load(
            program: RecordedProgram?,
            item: RecordedItem?,
            onLoaded: (SeriesPlaylist?) -> Unit,
        ) {
            val programName = program?.name ?: item?.name
            if (programName == null) {
                onLoaded(null)
                return
            }
            val seriesTitle = SeriesTitleExtractor.extract(programName)
            if (seriesTitle.isEmpty()) {
                Log.d(TAG, "load: series title not found in '$programName'")
                onLoaded(null)
                return
            }
            val currentId = program?.id ?: item!!.id
            if (program != null) {
                loadV1(seriesTitle, currentId, mutableListOf(), 0, 0, onLoaded)
            } else {
                loadV2(seriesTitle, currentId, mutableListOf(), 0, 0, onLoaded)
            }
        }

        /** EPGStation v1 用。1ページ取得しては [shouldFetchMore] の判断で自分を呼び直す。 */
        private fun loadV1(
            seriesTitle: String,
            currentId: Long,
            collected: MutableList<SeriesEntry>,
            fetchedCount: Long,
            page: Int,
            onLoaded: (SeriesPlaylist?) -> Unit,
        ) {
            val api = EpgStation.api
            if (api == null) {
                onLoaded(null)
                return
            }
            api.getRecorded(limit = PAGE_SIZE, offset = fetchedCount, keyword = seriesTitle)
                .enqueue(object : Callback<GetRecordedResponse> {
                    override fun onResponse(
                        call: Call<GetRecordedResponse>,
                        response: Response<GetRecordedResponse>
                    ) {
                        val body = response.body()
                        if (body == null) {
                            Log.w(TAG, "loadV1: empty response (HTTP${response.code()})")
                            onLoaded(null)
                            return
                        }
                        body.recorded
                            .filter { matchesSeries(it.name, seriesTitle) }
                            .mapTo(collected) { SeriesEntry.of(it) }
                        val fetched = fetchedCount + body.recorded.size
                        if (shouldFetchMore(collected, currentId, fetched, body.total, page)) {
                            loadV1(seriesTitle, currentId, collected, fetched, page + 1, onLoaded)
                        } else {
                            onLoaded(build(collected, seriesTitle))
                        }
                    }

                    override fun onFailure(call: Call<GetRecordedResponse>, t: Throwable) {
                        Log.w(TAG, "loadV1: getRecorded failed: ${t.message}")
                        onLoaded(null)
                    }
                })
        }

        /** EPGStation v2 用。[loadV1] と同じ流れ。 */
        private fun loadV2(
            seriesTitle: String,
            currentId: Long,
            collected: MutableList<SeriesEntry>,
            fetchedCount: Long,
            page: Int,
            onLoaded: (SeriesPlaylist?) -> Unit,
        ) {
            val api = EpgStationV2.api
            if (api == null) {
                onLoaded(null)
                return
            }
            api.getRecorded(offset = fetchedCount, limit = PAGE_SIZE, keyword = seriesTitle)
                .enqueue(object : Callback<Records> {
                    override fun onResponse(call: Call<Records>, response: Response<Records>) {
                        val body = response.body()
                        if (body == null) {
                            Log.w(TAG, "loadV2: empty response (HTTP${response.code()})")
                            onLoaded(null)
                            return
                        }
                        body.records
                            .filter { matchesSeries(it.name, seriesTitle) }
                            .mapTo(collected) { SeriesEntry.of(it) }
                        val fetched = fetchedCount + body.records.size
                        if (shouldFetchMore(collected, currentId, fetched, body.total.toLong(), page)) {
                            loadV2(seriesTitle, currentId, collected, fetched, page + 1, onLoaded)
                        } else {
                            onLoaded(build(collected, seriesTitle))
                        }
                    }

                    override fun onFailure(call: Call<Records>, t: Throwable) {
                        Log.w(TAG, "loadV2: getRecorded failed: ${t.message}")
                        onLoaded(null)
                    }
                })
        }

        /**
         * 続きを取りに行くべきか。
         *
         * 検索結果は録画開始時刻の新しい順に返るので、必要なのは
         *  - 今見ている回が見つかっていること
         *  - それより古い回が1つは見つかっていること(前の回が次のページに居ることがある)
         * の2つ。新しい側の隣は、今見ている回が見つかった時点で同じページか前のページに
         * 揃っているので確認しなくてよい。
         */
        private fun shouldFetchMore(
            collected: List<SeriesEntry>,
            currentId: Long,
            fetchedCount: Long,
            total: Long,
            page: Int,
        ): Boolean {
            if (fetchedCount >= total) return false
            if (page + 1 >= MAX_PAGES) return false
            val current = collected.firstOrNull { it.id == currentId } ?: return true
            return collected.none { it.startAt < current.startAt }
        }

        /** 検索で集めた回を録画順に並べる。同時刻の録画は id 順にして並びを一意にする。 */
        fun build(candidates: List<SeriesEntry>, seriesTitle: String): SeriesPlaylist =
            SeriesPlaylist(candidates.sortedWith(compareBy({ it.startAt }, { it.id })), seriesTitle)

        /**
         * 番組名がシリーズ名を含むか。
         *
         * 比較の前に NFKC 正規化する。EPGStation は検索語を半角に寄せてから
         * `halfWidthName` と突き合わせるため、全角/半角の違いしかない同じシリーズが
         * サーバー側では当たるのにこちらで落ちる、という食い違いを防ぐ。
         */
        fun matchesSeries(programName: String, seriesTitle: String): Boolean =
            normalize(programName).contains(normalize(seriesTitle))

        private fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
    }
}

/**
 * シリーズ内の録画1本。EPGStation のバージョンで番組を表す型が違うので、その差をここで吸収する。
 * どちらか一方だけが非 null になる。
 */
class SeriesEntry private constructor(
    val recordedProgram: RecordedProgram?,
    val recordedItem: RecordedItem?,
) {
    val id: Long = recordedProgram?.id ?: recordedItem!!.id
    val startAt: Long = recordedProgram?.startAt ?: recordedItem!!.startAt
    val name: String = recordedProgram?.name ?: recordedItem!!.name

    /**
     * この回を再生するときの再生対象を決める。今見ているのと同じものを選ぶ——[preferTs] で
     * TS/エンコード済みの別を、[preferredName] でエンコード済みの中の画質(プロファイル名)を
     * 引き継ぐ。同じものが無ければ、あるほうへ落とす。再生できるファイルが1つも無ければ null。
     */
    fun resolvePlaybackTarget(preferTs: Boolean, preferredName: String?): PlaybackTarget? {
        recordedProgram?.let { program ->
            val ts = if (program.original) {
                PlaybackTarget(VideoDetailsFragment.ACTION_WATCH_ORIGINAL_TS, isTs = true)
            } else {
                null
            }
            val encodedList = program.encoded.orEmpty()
            val encoded = (encodedList.firstOrNull { it.name == preferredName } ?: encodedList.firstOrNull())
                ?.let { PlaybackTarget(it.encodedId, isTs = false) }
            return if (preferTs) ts ?: encoded else encoded ?: ts
        }
        recordedItem?.let { item ->
            val files = item.videoFiles.orEmpty()
            val ts = pickFile(files.filter { it.type == TYPE_TS }, preferredName)
                ?.let { PlaybackTarget(it.id, isTs = true) }
            val encoded = pickFile(files.filter { it.type != TYPE_TS }, preferredName)
                ?.let { PlaybackTarget(it.id, isTs = false) }
            return if (preferTs) ts ?: encoded else encoded ?: ts
        }
        return null
    }

    private fun pickFile(files: List<VideoFile>, preferredName: String?): VideoFile? =
        files.firstOrNull { it.name == preferredName } ?: files.firstOrNull()

    companion object {
        private const val TYPE_TS = "ts"

        fun of(program: RecordedProgram) = SeriesEntry(program, null)
        fun of(item: RecordedItem) = SeriesEntry(null, item)
    }
}

/** 再生するファイルの指定。[actionId] は [DetailsActivity.ACTIONID] に載せる値。 */
data class PlaybackTarget(val actionId: Long, val isTs: Boolean)
