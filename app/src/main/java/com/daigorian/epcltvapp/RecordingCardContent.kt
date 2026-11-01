package com.daigorian.epcltvapp

/** Keep the recording time visible even when the card's secondary text is one line. */
object RecordingCardContent {
    fun text(channelName: String, timing: String, description: String?, shortened: Boolean): String =
        if (shortened) timing else buildString {
            if (channelName.isNotEmpty()) { append(channelName); append('\n') }
            append(timing)
            if (!description.isNullOrEmpty()) { append('\n'); append(description) }
        }
}
