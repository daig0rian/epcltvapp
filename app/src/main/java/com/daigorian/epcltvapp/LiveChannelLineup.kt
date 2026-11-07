package com.daigorian.epcltvapp

import android.util.Log
import com.daigorian.epcltvapp.epgstationv2caller.ChannelItem
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.Schedule
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * ライブ視聴中に左右キーで局を変えるための、局一覧と番組表の控え。
 *
 * [PlaybackActivity] が1つ持ち、局を変えるたびに作り直される [PlaybackVideoFragment] をまたいで使い回す。
 * 並びはホームのライブ視聴の行と同じ基準で決める（[LiveLineup.neighbor]）。番組表は
 * [LiveLineup.SCHEDULE_WINDOW_MS] 先まで取ってキーを押した時刻で引くので、見ている間にサブチャンネルの番組が
 * 始まったり終わったりしても、行と同じ並びになる。
 */
class LiveChannelLineup {

    /** 移る先の局と、その局でいま放送中の番組名（番組表に無ければ null）。 */
    data class Target(val channel: ChannelItem, val programName: String?)

    private var channels: List<ChannelItem>? = null
    private var schedules: List<Schedule> = emptyList()
    /** [schedules] が当てになる時刻の上限。過ぎたら取り直す。 */
    private var schedulesUntil = Long.MIN_VALUE

    /** 取得中に頼まれた分。取得が終わったところで呼ぶ。後から頼まれたものだけを残す。 */
    private var pending: (() -> Unit)? = null
    private var loading = false

    /** 控えが無いか古ければ、先に取っておく。最初に左右キーを押したときに待たせないため。 */
    fun prefetch() {
        if (!isFresh()) load()
    }

    /**
     * [currentId] の隣の局を求めて [onResult] へ渡す。移れる局が無ければ null を渡す。
     * 控えが無いか古ければ取り直してから求め、取れなかったら [onFailure] を呼ぶ。
     * @param forward 右（行の後ろ）へ移るなら true
     * @param hidesPaid 「有料放送の非表示」が ON なら true
     */
    fun neighbor(
        currentId: Long,
        forward: Boolean,
        hiddenIds: Set<String>,
        hidesPaid: Boolean,
        onResult: (Target?) -> Unit,
        onFailure: () -> Unit
    ) {
        val resolve = {
            val loaded = channels
            if (loaded == null || !isFresh()) {
                onFailure()
            } else {
                val programs = LiveLineup.currentPrograms(schedules, System.currentTimeMillis())
                val paidIds = if (hidesPaid) LiveLineup.paidIds(programs) else emptySet()
                val channel = LiveLineup.neighbor(loaded, hiddenIds, programs.keys, currentId, forward, paidIds)
                onResult(channel?.let { Target(it, programs[it.id]?.name) })
            }
        }
        if (isFresh()) {
            resolve()
        } else {
            pending = resolve
            load()
        }
    }

    private fun isFresh(): Boolean = channels != null && System.currentTimeMillis() < schedulesUntil

    /** 局一覧と番組表を並べて取りに行き、両方揃ったら [pending] を呼ぶ。 */
    private fun load() {
        if (loading) return
        val api = EpgStationV2.api
        if (api == null) {
            finishLoading()
            return
        }
        loading = true
        val now = System.currentTimeMillis()
        val until = now + LiveLineup.SCHEDULE_WINDOW_MS
        var fetchedChannels: List<ChannelItem>? = null
        var fetchedSchedules: List<Schedule>? = null
        var remaining = 2
        // Retrofit のコールバックはメインスレッドで呼ばれるため、この件数の数え方で足りる。
        val countDown = {
            remaining -= 1
            if (remaining == 0) {
                val loadedChannels = fetchedChannels
                val loadedSchedules = fetchedSchedules
                if (loadedChannels != null && loadedSchedules != null) {
                    channels = loadedChannels
                    schedules = loadedSchedules
                    schedulesUntil = until
                    Log.d(TAG, "load: 局 ${loadedChannels.size}、番組表の局 ${loadedSchedules.size}")
                } else {
                    Log.w(TAG, "load: 取得に失敗 channels=${loadedChannels != null} schedules=${loadedSchedules != null}")
                }
                finishLoading()
            }
        }
        api.getChannels().enqueue(object : Callback<List<ChannelItem>> {
            override fun onResponse(call: Call<List<ChannelItem>>, response: Response<List<ChannelItem>>) {
                fetchedChannels = response.body()
                countDown()
            }
            override fun onFailure(call: Call<List<ChannelItem>>, t: Throwable) = countDown()
        })
        api.getSchedules(startAt = now, endAt = until).enqueue(object : Callback<List<Schedule>> {
            override fun onResponse(call: Call<List<Schedule>>, response: Response<List<Schedule>>) {
                fetchedSchedules = response.body()
                countDown()
            }
            override fun onFailure(call: Call<List<Schedule>>, t: Throwable) = countDown()
        })
    }

    private fun finishLoading() {
        loading = false
        val callback = pending
        pending = null
        callback?.invoke()
    }

    companion object {
        private const val TAG = "LiveChannelLineup"
    }
}
