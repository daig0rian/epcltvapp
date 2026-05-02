package com.daigorian.epcltvapp

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.daigorian.epcltvapp.epgstationcaller.EpgStation
import com.daigorian.epcltvapp.epgstationcaller.RecordedProgram
import com.daigorian.epcltvapp.epgstationv2caller.EpgStationV2
import com.daigorian.epcltvapp.epgstationv2caller.RecordedItem

class PlaybackActivity : ComponentActivity() {

    private val viewModel: PlaybackViewModel by viewModels()

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recordedProgram = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM, RecordedProgram::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(DetailsActivity.RECORDEDPROGRAM) as RecordedProgram?
        }
        val recordedItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(DetailsActivity.RECORDEDITEM, RecordedItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(DetailsActivity.RECORDEDITEM) as RecordedItem?
        }
        val actionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(DetailsActivity.ACTIONID, java.lang.Long::class.java)?.toLong() ?: 0L
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(DetailsActivity.ACTIONID) as? Long ?: 0L
        }

        val title       = recordedProgram?.name        ?: recordedItem?.name        ?: ""
        val description = recordedProgram?.description ?: recordedItem?.description ?: ""

        val movieUrl = if (recordedProgram != null) {
            if (actionId == VideoDetailsFragment.ACTION_WATCH_ORIGINAL_TS) {
                EpgStation.getTsVideoURL(recordedProgram.id.toString())
            } else {
                EpgStation.getEncodedVideoURL(recordedProgram.id.toString(), actionId.toString())
            }
        } else {
            EpgStationV2.getVideoURL(actionId.toString())
        }

        viewModel.setMedia(Uri.parse(movieUrl))

        setContent {
            MaterialTheme {
                PlaybackScreen(
                    viewModel    = viewModel,
                    title        = title,
                    description  = description,
                    onBack       = { finish() },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.pause()
    }
}
