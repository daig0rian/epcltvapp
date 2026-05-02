@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.daigorian.epcltvapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private const val CONTROLS_HIDE_DELAY_MS = 3_000L

@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel,
    title: String,
    description: String,
    onBack: () -> Unit,
) {
    val isPlaying       by viewModel.isPlaying.collectAsState()
    val isBuffering     by viewModel.isBuffering.collectAsState()
    val duration        by viewModel.duration.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val isCompleted     by viewModel.isCompleted.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsFocused by remember { mutableStateOf(false) }
    var focusOnControls by remember { mutableStateOf(false) }
    val videoFocusRequester    = remember { FocusRequester() }
    val controlsFocusRequester = remember { FocusRequester() }

    // 再生完了で自動的に戻る
    LaunchedEffect(isCompleted) {
        if (isCompleted) onBack()
    }

    // 再生中はコントロールを 3 秒後に自動非表示（ボタンにフォーカスがある間は抑制）
    LaunchedEffect(controlsVisible, isPlaying, controlsFocused) {
        if (controlsVisible && isPlaying && !controlsFocused) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    // コントロールが非表示になったらビデオ領域にフォーカスを戻す
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible && controlsFocused) {
            controlsFocused = false
            videoFocusRequester.requestFocus()
        }
    }

    // AnimatedVisibility のコンポジション更新後にコントロールへフォーカスを移す
    LaunchedEffect(focusOnControls, controlsVisible) {
        if (focusOnControls && controlsVisible) {
            controlsFocusRequester.requestFocus()
            focusOnControls = false
        }
    }

    // 初回表示時にフォーカスを取得
    LaunchedEffect(Unit) {
        videoFocusRequester.requestFocus()
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(videoFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    controlsVisible = true
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (!controlsFocused) { viewModel.togglePlayPause(); true } else false
                        }
                        Key.DirectionRight -> {
                            if (!controlsFocused) { viewModel.fastForward(); true } else false
                        }
                        Key.DirectionLeft -> {
                            if (!controlsFocused) { viewModel.rewind(); true } else false
                        }
                        Key.DirectionDown -> {
                            controlsFocused = true
                            focusOnControls = true
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // 映像レイヤー
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }.also { viewModel.attachVideoLayout(it) }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { viewModel.detachVideoLayout() }
        )

        // バッファリングインジケーター
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // コントロールオーバーレイ（画面下部、3秒で自動非表示）
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(horizontal = 48.dp, vertical = 32.dp)
            ) {
                Column {
                    // タイトルと説明
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    if (description.isNotEmpty()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 2,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // プログレスバーと時刻
                    val effective = viewModel.effectiveDuration()
                    if (effective > 0) {
                        LinearProgressIndicator(
                            progress = { (currentPosition.toFloat() / effective).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatMs(currentPosition),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = formatMs(effective),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 操作ボタン
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                    controlsFocused = false
                                    videoFocusRequester.requestFocus()
                                    true
                                } else false
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.rewind(); controlsVisible = true }) {
                            Icon(
                                Icons.Default.FastRewind,
                                contentDescription = "Rewind 10s",
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(
                            onClick = { viewModel.togglePlayPause(); controlsVisible = true },
                            modifier = Modifier.size(56.dp).focusRequester(controlsFocusRequester),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Spacer(Modifier.width(24.dp))
                        IconButton(onClick = { viewModel.fastForward(); controlsVisible = true }) {
                            Icon(
                                Icons.Default.FastForward,
                                contentDescription = "FastForward 10s",
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.width(48.dp))
                        IconButton(onClick = { viewModel.toggleSubtitles(); controlsVisible = true }) {
                            Icon(
                                Icons.Default.ClosedCaption,
                                contentDescription = "Subtitles",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
