package com.daigorian.epcltvapp

/**
 * GetRecordedResponse データクラス. EPGStation API の getRecorded() の応答と同じ構造
 */
data class GetRecordedResponse(
    val recorded: List<RecordedProgram>,
    val total: Int
)