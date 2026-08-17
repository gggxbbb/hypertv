package icu.gxb.hypertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import icu.gxb.hypertv.player.Channel
import icu.gxb.hypertv.player.PlayerController
import icu.gxb.hypertv.player.PlayerState
import kotlinx.coroutines.delay

/**
 * 播放页（ticket 04 + 05）：Media3 PlayerView（SurfaceView 方案）经 AndroidView 接入 Compose。
 *
 * 按键语义（onPreviewKeyEvent，ticket 05）：
 * - 上下键：浮层收起时直接换台（按列表顺序）；浮层打开时在频道间移动焦点
 * - OK：浮层收起且无数字输入时呼出频道列表浮层；浮层内确认选中频道并换台收起；
 *   有数字输入时确认数字跳转
 * - 左右键：浮层内切换分组标签（全部/各分组/收藏），回绕
 * - 数字键（0-9 / 数字小键盘）：输入全局频道号（orderIndex+1，与分组无关），
 *   OK 确认或 2s 无按键自动跳转（spec 7.2）；浮层打开时同样生效并收起浮层
 * - 返回键：浮层内仅收起不换台；浮层外先取消数字输入
 *
 * 频道列表浮层：LazyColumn 虚拟化（5000+ 频道），行内 Coil AsyncImage 异步加载台标
 * （磁盘缓存由 Coil 3 默认 ImageLoader 提供）；当前播放频道高亮。
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
    val groups by controller.groups.collectAsState()

    var showChannelName by remember { mutableStateOf(false) }
    var showSignalLost by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val overlay = remember { ChannelListOverlayState() }
    val numberInput = remember { ChannelNumberController(scope) }
    val listState = rememberLazyListState()
    val tabListState = rememberLazyListState()

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
    val currentChannelId = currentChannel?.id

    // 标签序列与当前标签过滤后的频道列表
    val tabs = remember(groups) { ChannelListFilter.tabs(groups) }
    val filteredChannels = remember(channels, overlay.selectedTab) {
        ChannelListFilter.filter(channels, overlay.selectedTab)
    }

    // 数字跳转：复用 PlayerController.switchToIndex（频道号 = index + 1，超范围取模回绕）
    val latestChannels by rememberUpdatedState(channels)
    LaunchedEffect(Unit) {
        numberInput.onJump = { number ->
            val index = ChannelNumberMapping.toIndex(number, latestChannels.size)
            if (index != null) controller.switchToIndex(index)
            overlay.close()
        }
    }

    // 浮层打开/标签切换：焦点定位到当前播放频道（或列表首项）并滚动
    LaunchedEffect(overlay.isOpen, overlay.selectedTab) {
        if (!overlay.isOpen) return@LaunchedEffect
        val idx = filteredChannels.indexOfFirst { it.id == currentChannelId }
            .let { if (it >= 0) it else 0 }
        overlay.setFocus(filteredChannels.getOrNull(idx)?.id)
        if (filteredChannels.isNotEmpty()) listState.scrollToItem(idx)
    }

    // 焦点行自动滚动到可见
    LaunchedEffect(overlay.focusedChannelId) {
        if (!overlay.isOpen) return@LaunchedEffect
        val idx = filteredChannels.indexOfFirst { it.id == overlay.focusedChannelId }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    // 标签行：选中标签滚动到可见
    LaunchedEffect(overlay.selectedTab) {
        if (!overlay.isOpen) return@LaunchedEffect
        val idx = tabs.indexOf(overlay.selectedTab)
        if (idx >= 0) tabListState.animateScrollToItem(idx)
    }

    val handler = PlayerKeyHandler(controller, overlay, numberInput)
    handler.currentChannelId = currentChannelId
    handler.tabs = tabs
    handler.filteredChannels = filteredChannels
    handler.onConfirmSelection = {
        val id = overlay.focusedChannelId
        if (id != null) controller.play(id)
        overlay.close()
    }

    val numberDigits by numberInput.digits.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event -> handler.handle(event) },
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

        // 数字键输入提示（右上角）
        AnimatedVisibility(
            visible = numberDigits.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 32.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = NUMBER_BG_ALPHA))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "频道 $numberDigits",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        AnimatedVisibility(
            // 浮层打开时频道名信息已在列表中体现，不再叠加显示
            visible = showChannelName && !overlay.isOpen,
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

        // 频道列表浮层（屏幕下方半透明面板）
        AnimatedVisibility(
            visible = overlay.isOpen,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(OVERLAY_HEIGHT_FRACTION),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ChannelListPanel(
                channels = filteredChannels,
                tabs = tabs,
                selectedTab = overlay.selectedTab,
                focusedChannelId = overlay.focusedChannelId,
                currentChannelId = currentChannelId,
                listState = listState,
                tabListState = tabListState,
            )
        }
    }
}

/**
 * 频道列表浮层内容：分组标签行（LazyRow）+ 频道列表（LazyColumn，虚拟化）。
 * 台标用 Coil AsyncImage 异步加载，占位为统一灰色块（避免每帧重组/同步加载）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelListPanel(
    channels: List<Channel>,
    tabs: List<String>,
    selectedTab: String,
    focusedChannelId: String?,
    currentChannelId: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    tabListState: androidx.compose.foundation.lazy.LazyListState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = PANEL_BG_ALPHA))
            .padding(vertical = 16.dp),
    ) {
        // 分组标签行（"全部" + 各分组 + "收藏"）
        LazyRow(
            state = tabListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs) { tab ->
                val selected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = tab,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (channels.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无频道",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { _, channel ->
                    ChannelRow(
                        channel = channel,
                        channelNumber = channel.orderIndex + 1,
                        isFocused = channel.id == focusedChannelId,
                        isCurrent = channel.id == currentChannelId,
                    )
                }
            }
        }
    }
}

/** 频道行：频道号（orderIndex+1，固定宽度样式）+ 台标（统一占位灰块）+ 频道名 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    channelNumber: Int,
    isFocused: Boolean,
    isCurrent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = FOCUS_BG_ALPHA)
                    isCurrent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CURRENT_BG_ALPHA)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channelNumber.toString().padStart(3, ' '),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = NUMBER_TEXT_ALPHA),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp),
        )
        // 台标：统一灰色块占位 + Coil 异步加载（磁盘缓存默认开启）
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(LOGO_PLACEHOLDER_COLOR),
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = channel.name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
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

/**
 * 遥控器按键分发（ticket 05）。浮层打开时切换为"列表导航"语义，
 * 收起时恢复"直接换台"语义；数字键两种状态下都累积输入。
 *
 * 字段（tabs/filteredChannels/currentChannelId/onConfirmSelection）由调用方
 * 在每次重组时同步，避免闭包捕获过期状态。
 */
private class PlayerKeyHandler(
    private val controller: PlayerController,
    private val overlay: ChannelListOverlayState,
    private val numberInput: ChannelNumberController,
) {
    var tabs: List<String> = emptyList()
    var filteredChannels: List<Channel> = emptyList()
    var currentChannelId: String? = null
    var onConfirmSelection: () -> Unit = {}

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when (val code = event.key.nativeKeyCode) {
            in DIGIT_CODES -> {
                numberInput.onDigit(code - AndroidKeyEvent.KEYCODE_0)
                true
            }
            in NUMPAD_DIGIT_CODES -> {
                numberInput.onDigit(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                numberInput.clear() // 换台/移动焦点打断数字输入
                if (overlay.isOpen) overlay.moveFocus(-1, filteredChannels)
                else controller.switchTo(-1)
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                numberInput.clear()
                if (overlay.isOpen) overlay.moveFocus(1, filteredChannels)
                else controller.switchTo(1)
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                numberInput.clear()
                if (overlay.isOpen) overlay.switchTab(-1, tabs)
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                numberInput.clear()
                if (overlay.isOpen) overlay.switchTab(1, tabs)
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                when {
                    numberInput.hasDigits() -> numberInput.confirm() // 数字输入时 OK 确认跳转
                    overlay.isOpen -> onConfirmSelection() // 浮层内 OK 确认选中频道
                    else -> overlay.open() // 呼出频道列表浮层
                }
                true
            }
            AndroidKeyEvent.KEYCODE_BACK -> {
                if (overlay.isOpen) {
                    // 返回键仅收起不换台（先取消挂起的数字输入，避免 2s 后误跳转）
                    numberInput.clear()
                    overlay.close()
                } else {
                    numberInput.clear() // 先取消数字输入，再次返回才交给系统退出
                    return false
                }
                true
            }
            else -> false
        }
    }
}

private const val CHANNEL_NAME_SHOW_MS = 2_000L
private const val SIGNAL_LOST_SHOW_MS = 3_000L
private const val CHANNEL_NAME_BG_ALPHA = 0.6f
private const val SIGNAL_LOST_BG_ALPHA = 0.6f
private const val NUMBER_BG_ALPHA = 0.8f
private const val PANEL_BG_ALPHA = 0.75f
private const val FOCUS_BG_ALPHA = 0.45f
private const val CURRENT_BG_ALPHA = 0.25f
private const val NUMBER_TEXT_ALPHA = 0.6f

/** 浮层高度占屏比例 */
private const val OVERLAY_HEIGHT_FRACTION = 0.55f

/** 台标统一占位灰块颜色 */
private val LOGO_PLACEHOLDER_COLOR = Color(0xFF3A3A3A)

/** 主键盘数字键：KEYCODE_0(7)..KEYCODE_9(16) */
private val DIGIT_CODES = AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9

/** 数字小键盘：KEYCODE_NUMPAD_0(144)..KEYCODE_NUMPAD_9(153) */
private val NUMPAD_DIGIT_CODES = AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9
