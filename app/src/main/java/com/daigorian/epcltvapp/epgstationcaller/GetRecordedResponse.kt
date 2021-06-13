package com.daigorian.epcltvapp.epgstationcaller

import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram

/**
 * GetRecordedResponse データクラス. EPGStation API の getRecorded() の応答と同じ構造
 */
data class GetRecordedResponse(
    val recorded: List<RecordedProgram>,
    val total: Long
)