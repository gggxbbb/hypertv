package icu.gxb.hypertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import icu.gxb.hypertv.player.PlayerController
import icu.gxb.hypertv.player.PlayerState
import kotlinx.coroutines.delay

/**
 * 播放页：Media3 PlayerView（SurfaceView 方案，Android TV 上可靠）经 AndroidView
 * 接入 Compose。遥控器上下键按列表顺序换台（ticket 05 才做频道列表浮层）。
 *
 * - 频道名浮层：启动/换台时底部显示约 2s 渐隐
 * - "信号中断"浮层：失败自动跳转时居中显示约 3s 渐隐（ADR-0007）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    player: ExoPlayer,
    controller: PlayerController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val channels by controller.channels.collectAsState()

    var showChannelName by remember { mutableStateOf(false) }
    var showSignalLost by remember { mutableStateOf(false) }

    // 启动/换台时显示频道名，2s 后渐隐
    LaunchedEffect(state) {
        when (state) {
            is PlayerState.Preparing, is PlayerState.Playing, is PlayerState.AutoAdvancing -> {
                showChannelName = true
                delay(CHANNEL_NAME_SHOW_MS)
                showChannelName = false
            }
            else -> Unit
        }
    }

    // 自动跳转（重试耗尽）时显示"信号中断"，3s 后渐隐
    LaunchedEffect(state) {
        if (state is PlayerState.AutoAdvancing) {
            showSignalLost = true
            delay(SIGNAL_LOST_SHOW_MS)
            showSignalLost = false
        }
    }

    val currentChannel = channels.firstOrNull { it.id == stateChannelId(state) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event -> handleKeyEvent(event, controller) },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // 遥控器控制，不显示触摸播放控制条
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = showChannelName,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = CHANNEL_NAME_BG_ALPHA))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = currentChannel?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        AnimatedVisibility(
            visible = showSignalLost,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = SIGNAL_LOST_BG_ALPHA))
                    .padding(horizontal = 32.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "信号中断",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 各状态对应的"当前频道" id，供浮层取名 */
private fun stateChannelId(state: PlayerState): String? = when (state) {
    is PlayerState.Preparing -> state.channelId
    is PlayerState.Playing -> state.channelId
    is PlayerState.ErrorRetrying -> state.channelId
    is PlayerState.AutoAdvancing -> state.toChannelId
    else -> null
}

private fun handleKeyEvent(event: KeyEvent, controller: PlayerController): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key.nativeKeyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP,
        AndroidKeyEvent.KEYCODE_CHANNEL_UP,
        -> {
            controller.switchTo(-1)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
        -> {
            controller.switchTo(1)
            true
        }
        else -> false
    }
}

private const val CHANNEL_NAME_SHOW_MS = 2_000L
private const val SIGNAL_LOST_SHOW_MS = 3_000L
private const val CHANNEL_NAME_BG_ALPHA = 0.6f
private const val SIGNAL_LOST_BG_ALPHA = 0.6f
