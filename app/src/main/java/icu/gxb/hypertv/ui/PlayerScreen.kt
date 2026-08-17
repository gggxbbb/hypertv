package icu.gxb.hypertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import icu.gxb.hypertv.BuildConfig
import icu.gxb.hypertv.data.repository.HypertvRepository
import icu.gxb.hypertv.net.getLocalIpv4
import icu.gxb.hypertv.player.Channel
import icu.gxb.hypertv.player.FavoriteStore
import icu.gxb.hypertv.player.PlayerController
import icu.gxb.hypertv.player.PlayerState
import icu.gxb.hypertv.server.SERVER_PORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 播放页（ticket 04 + 05 + 06）：Media3 PlayerView（SurfaceView 方案）经 AndroidView 接入 Compose。
 *
 * 按键语义（onPreviewKeyEvent，ticket 05/06）：
 * - 上下键：浮层收起时直接换台（按列表顺序）；浮层打开时在频道间移动焦点；
 *   主菜单/收藏列表页内移动对应列表焦点
 * - OK：浮层收起且无数字输入时呼出频道列表浮层；浮层内确认选中频道并换台收起；
 *   有数字输入时确认数字跳转；主菜单内确认进入收藏列表；收藏列表页内确认播放并返回
 * - 左右键：浮层内切换分组标签（全部/各分组/收藏），回绕；收藏列表页内无功能（spec 7.2）
 * - 数字键（0-9 / 数字小键盘）：播放页输入全局频道号，OK 确认或 2s 无按键自动跳转；
 *   主菜单/收藏列表页内无功能（不累积输入）
 * - 星号键（*）/ 红色功能键（ticket 06）：播放页切换当前播放频道收藏；浮层打开时
 *   切换浮层焦点频道收藏；收藏列表页内切换焦点频道收藏，均显示短暂提示
 * - Menu 键（ticket 06）：播放页呼出主菜单（半透明面板），上下键移动焦点、OK 进入
 *   节目表/收藏列表/关于（回放为 v2 占位不可聚焦）、返回键关闭回到播放页
 * - 返回键：浮层内仅收起不换台；主菜单/收藏列表页/关于页内关闭回到播放页；浮层外先取消数字输入
 *
 * 频道列表浮层：LazyColumn 虚拟化（5000+ 频道），行内 Coil AsyncImage 异步加载台标；
 * 主菜单与收藏列表页以全屏/局部覆盖层叠加在本页之上（简单状态机，不引入导航库）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    player: ExoPlayer,
    controller: PlayerController,
    favoriteStore: FavoriteStore,
    repository: HypertvRepository,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val channels by controller.channels.collectAsState()
    val groups by controller.groups.collectAsState()
    val favorites by favoriteStore.favorites.collectAsState()

    var showChannelName by remember { mutableStateOf(false) }
    var showSignalLost by remember { mutableStateOf(false) }
    var favoriteHint by remember { mutableStateOf<FavoriteHint?>(null) }
    var hintSeq by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    val overlay = remember { ChannelListOverlayState() }
    val numberInput = remember { ChannelNumberController(scope) }
    val menu = remember { MainMenuState() }
    val favoritesScreen = remember { FavoritesScreenState() }
    val infoOverlay = remember { InfoOverlayState() }
    val guide = remember { EpgGuideState() }
    val about = remember { AboutScreenState() }
    // 关于页信息只读展示（本地读取，不依赖 Ktor 服务状态，ADR-0002）
    val aboutInfo = remember {
        AboutInfo(versionName = BuildConfig.VERSION_NAME, ip = getLocalIpv4(), port = SERVER_PORT)
    }
    val listState = rememberLazyListState()
    val tabListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val guideListState = rememberLazyListState()

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

    // 收藏提示：每次切换更新文本并重启 1.8s 渐隐窗口（快速连按时同样正确）
    LaunchedEffect(favoriteHint?.seq) {
        val hint = favoriteHint
        if (hint != null) {
            delay(FAVORITE_HINT_SHOW_MS)
            favoriteHint = null
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

    // 收藏列表页：进入或收藏变化时，焦点定位到当前播放频道（不在收藏中则保持原焦点，
    // 仍无则首项）并滚动；OK 播放后由按键分发关闭
    LaunchedEffect(favoritesScreen.isOpen, favorites) {
        if (!favoritesScreen.isOpen) return@LaunchedEffect
        var idx = favorites.indexOfFirst { it.id == currentChannelId }
        if (idx < 0) idx = favorites.indexOfFirst { it.id == favoritesScreen.focusedChannelId }
        if (idx < 0) idx = 0
        favoritesScreen.setFocus(favorites.getOrNull(idx)?.id)
        if (favorites.isNotEmpty()) favoritesListState.scrollToItem(idx.coerceIn(0, favorites.lastIndex))
    }

    // 收藏列表页：焦点行自动滚动到可见
    LaunchedEffect(favoritesScreen.focusedChannelId) {
        if (!favoritesScreen.isOpen) return@LaunchedEffect
        val idx = favorites.indexOfFirst { it.id == favoritesScreen.focusedChannelId }
        if (idx >= 0) favoritesListState.animateScrollToItem(idx)
    }

    // Info 浮层：查询当前频道正在播放的节目（[now-δ, now+δ] 窗口内取正在播放的）。
    // 换台（currentChannelId/state 变化）时刷新为新频道节目。
    LaunchedEffect(infoOverlay.isOpen, currentChannelId, state) {
        if (!infoOverlay.isOpen) return@LaunchedEffect
        val epgId = currentChannel?.epgId
        if (epgId == null) {
            infoOverlay.updateProgram(null)
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        val programs = repository.programs(
            epgId,
            now - INFO_QUERY_DELTA_MS,
            now + INFO_QUERY_DELTA_MS,
        ).first()
        infoOverlay.updateProgram(findCurrentProgram(programs, now))
    }

    // Info 浮层：超时自动收起（再次按 Info 由按键分发收起；换台后重新计时）
    LaunchedEffect(infoOverlay.isOpen, currentChannelId) {
        if (!infoOverlay.isOpen) return@LaunchedEffect
        delay(INFO_AUTO_CLOSE_MS)
        infoOverlay.onTimeout()
    }

    // Guide 节目加载：时间窗口或已加载频道数变化时查询并注入（N 个频道 × 6h 窗口，
    // 一次批量查询；左右键移动窗口、上下键到底翻页时重新加载）
    LaunchedEffect(guide.isOpen, guide.windowStartMs, guide.loadedChannelCount) {
        if (!guide.isOpen) return@LaunchedEffect
        val epgIds = channels.take(guide.loadedChannelCount).mapNotNull { it.epgId }.distinct()
        val programs = if (epgIds.isEmpty()) {
            emptyList()
        } else {
            repository.programsByChannelEpgIdsOnce(
                epgIds,
                guide.windowStartMs,
                guide.windowStartMs + WINDOW_DURATION_MS,
            )
        }
        guide.setPrograms(programs.groupBy { it.channelEpgId })
    }

    val handler = PlayerKeyHandler(
        controller, overlay, numberInput, menu, favoritesScreen, guide, infoOverlay, about, favoriteStore, scope,
    )
    handler.currentChannelId = currentChannelId
    handler.tabs = tabs
    handler.filteredChannels = filteredChannels
    handler.allChannels = channels
    handler.favorites = favorites
    handler.onConfirmSelection = {
        val id = overlay.focusedChannelId
        if (id != null) controller.play(id)
        overlay.close()
    }
    handler.onMenuConfirm = { index ->
        // index 0 节目表 / 1 收藏列表 / 2 关于；进入前打断数字输入，避免 2s 后误跳转
        numberInput.clear()
        when (index) {
            0 -> {
                menu.close()
                infoOverlay.close()
                guide.open(System.currentTimeMillis(), channels.size)
            }
            1 -> {
                menu.close()
                infoOverlay.close()
                favoritesScreen.open()
            }
            2 -> {
                menu.close()
                infoOverlay.close()
                about.open()
            }
        }
    }
    handler.onGuideConfirm = { channelId ->
        controller.play(channelId)
        guide.close()
    }
    handler.onToggleFavorite = { _, nowFavorite ->
        favoriteHint = FavoriteHint(text = if (nowFavorite) "已收藏" else "已取消收藏", seq = ++hintSeq)
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

        // 收藏切换提示（右上角，数字输入下方）
        AnimatedVisibility(
            visible = favoriteHint != null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 88.dp, end = 32.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = FAVORITE_HINT_BG_ALPHA))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = favoriteHint?.text.orEmpty(),
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
                    text = buildString {
                        if (currentChannel?.isFavorite == true) append("★ ")
                        append(currentChannel?.name.orEmpty())
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Info 节目信息浮层（屏幕上方，Info 键呼出/超时收起）
        AnimatedVisibility(
            visible = infoOverlay.isOpen,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            InfoOverlay(
                channelName = currentChannel?.name,
                program = infoOverlay.program,
            )
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

        // 主菜单（左侧半透明面板，播放页按 Menu 键呼出）
        AnimatedVisibility(
            visible = menu.isOpen,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            MainMenuPanel(selectedIndex = menu.selectedIndex)
        }

        // 收藏列表全屏页（主菜单"⭐ 收藏列表"进入）
        AnimatedVisibility(
            visible = favoritesScreen.isOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            FavoritesScreen(
                favorites = favorites,
                focusedChannelId = favoritesScreen.focusedChannelId,
                currentChannelId = currentChannelId,
                listState = favoritesListState,
            )
        }

        // 节目表全屏页（主菜单"节目表"进入，与收藏列表页同级模式）
        AnimatedVisibility(
            visible = guide.isOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            EpgGuideScreen(
                channels = channels,
                currentChannelId = currentChannelId,
                guide = guide,
                listState = guideListState,
            )
        }

        // 关于页全屏（主菜单"关于"进入，只读展示版本/IP/端口/二维码）
        AnimatedVisibility(
            visible = about.isOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AboutScreen(info = aboutInfo)
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

/** 各状态对应的"当前频道" id，供浮层取名 */
private fun stateChannelId(state: PlayerState): String? = when (state) {
    is PlayerState.Preparing -> state.channelId
    is PlayerState.Playing -> state.channelId
    is PlayerState.ErrorRetrying -> state.channelId
    is PlayerState.AutoAdvancing -> state.toChannelId
    else -> null
}

/** 收藏提示内容：文本 + 序号（序号变化触发 LaunchedEffect 重启 1.8s 渐隐窗口） */
private data class FavoriteHint(
    val text: String,
    val seq: Int,
)

/**
 * 遥控器按键分发（ticket 05 + 06）。全局键（星号/红键收藏、Menu 呼出主菜单）优先，
 * 其余按键按当前模式分发：主菜单 → 菜单导航；收藏列表页 → 列表导航；
 * 播放页（含频道列表浮层）→ 既有语义（上下键换台/浮层移动、数字键输入、OK 呼出浮层等）。
 *
 * 字段由调用方在每次重组时同步，避免闭包捕获过期状态。
 */
private class PlayerKeyHandler(
    private val controller: PlayerController,
    private val overlay: ChannelListOverlayState,
    private val numberInput: ChannelNumberController,
    private val menu: MainMenuState,
    private val favoritesScreen: FavoritesScreenState,
    private val guide: EpgGuideState,
    private val info: InfoOverlayState,
    private val about: AboutScreenState,
    private val favoriteStore: FavoriteStore,
    private val scope: CoroutineScope,
) {
    var tabs: List<String> = emptyList()
    var filteredChannels: List<Channel> = emptyList()
    /** 完整可见频道列表（Guide 行焦点用，与浮层过滤列表无关） */
    var allChannels: List<Channel> = emptyList()
    var favorites: List<Channel> = emptyList()
    var currentChannelId: String? = null
    var onConfirmSelection: () -> Unit = {}
    var onMenuConfirm: (Int) -> Unit = {}
    var onGuideConfirm: (String) -> Unit = {}
    var onToggleFavorite: (channelId: String, nowFavorite: Boolean) -> Unit = { _, _ -> }

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val code = event.key.nativeKeyCode

        // 全局：星号键/红键一键收藏/取消当前目标频道
        if (code == AndroidKeyEvent.KEYCODE_STAR || code == AndroidKeyEvent.KEYCODE_PROG_RED) {
            return handleFavoriteToggle()
        }
        // 全局：Menu 键呼出主菜单（仅播放页且各浮层/页面收起时）
        if (code == AndroidKeyEvent.KEYCODE_MENU) {
            if (!menu.isOpen && !favoritesScreen.isOpen && !overlay.isOpen && !guide.isOpen && !about.isOpen) {
                numberInput.clear() // 打断数字输入，避免进入菜单后 2s 误跳转
                menu.open()
            }
            return true
        }

        return when {
            menu.isOpen -> handleMenuKey(code)
            guide.isOpen -> handleGuideKey(code)
            favoritesScreen.isOpen -> handleFavoritesKey(code)
            about.isOpen -> handleAboutKey(code)
            else -> handlePlayerKey(code)
        }
    }

    /** 星号/红键：按当前模式取目标频道（收藏列表页/浮层取焦点频道，播放页取当前播放频道） */
    private fun handleFavoriteToggle(): Boolean {
        if (guide.isOpen || about.isOpen) return true // Guide/关于页内无收藏语义，消费不动作
        val targetId = when {
            favoritesScreen.isOpen -> favoritesScreen.focusedChannelId
            overlay.isOpen -> overlay.focusedChannelId ?: currentChannelId
            else -> currentChannelId
        }
        if (targetId == null) return false
        scope.launch {
            val nowFavorite = favoriteStore.toggle(targetId)
            onToggleFavorite(targetId, nowFavorite)
        }
        return true
    }

    /** 关于页导航：只读页，返回键关闭；其余键无功能（消费） */
    private fun handleAboutKey(code: Int): Boolean = when (code) {
        AndroidKeyEvent.KEYCODE_BACK -> {
            about.close()
            true
        }
        else -> true // 上下键/OK/数字键等无功能，消费
    }

    /** 主菜单导航：上下键移动焦点（仅启用项）、OK 确认、返回键关闭；左右键无功能 */
    private fun handleMenuKey(code: Int): Boolean = when (code) {
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
            menu.moveFocus(-1, MAIN_MENU_ENABLED_COUNT)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
            menu.moveFocus(1, MAIN_MENU_ENABLED_COUNT)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> {
            if (MAIN_MENU_ENABLED_COUNT > 0) onMenuConfirm(menu.selectedIndex)
            true
        }
        AndroidKeyEvent.KEYCODE_BACK -> {
            menu.close()
            true
        }
        else -> true // 左右键/数字键等无功能，消费
    }

    /**
     * 节目表（Guide）导航：上下键移动行焦点（到底自动翻页追加）、左右键移动时间窗口
     * （±1 小时）、OK 播放焦点频道并返回、返回键退出；数字键无功能（消费）。
     */
    private fun handleGuideKey(code: Int): Boolean = when (code) {
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
            guide.moveFocus(-1, allChannels.size)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
            guide.moveFocus(1, allChannels.size)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
            guide.moveWindow(-WINDOW_STEP_HOURS)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
            guide.moveWindow(WINDOW_STEP_HOURS)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> {
            val id = allChannels.getOrNull(guide.focusedRow)?.id
            if (id != null) onGuideConfirm(id)
            true
        }
        AndroidKeyEvent.KEYCODE_BACK -> {
            guide.close()
            true
        }
        else -> true // 左右键/数字键等无功能，消费
    }

    /** 收藏列表页导航：上下键移动焦点、OK 播放并返回、返回键关闭；左右键无功能（spec 7.2） */
    private fun handleFavoritesKey(code: Int): Boolean = when (code) {
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
            favoritesScreen.moveFocus(-1, favorites)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
            favoritesScreen.moveFocus(1, favorites)
            true
        }
        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true // 无功能，消费
        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> {
            val id = favoritesScreen.focusedChannelId
            if (id != null) {
                controller.play(id)
                favoritesScreen.close()
            }
            true
        }
        AndroidKeyEvent.KEYCODE_BACK -> {
            favoritesScreen.close()
            true
        }
        else -> true // 数字键等无功能，消费
    }

    /** 播放页（含频道列表浮层）语义：ticket 05 既有行为 */
    private fun handlePlayerKey(code: Int): Boolean {
        return when (code) {
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
            AndroidKeyEvent.KEYCODE_INFO -> {
                // Info 键：浮层已开则收起；否则浮层收起时呼出（频道列表浮层打开时不叠加）
                if (info.isOpen) info.close()
                else if (!overlay.isOpen) {
                    numberInput.clear()
                    info.open()
                }
                true
            }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                when {
                    numberInput.hasDigits() -> numberInput.confirm() // 数字输入时 OK 确认跳转
                    overlay.isOpen -> onConfirmSelection() // 浮层内 OK 确认选中频道
                    else -> {
                        info.close()
                        overlay.open() // 呼出频道列表浮层
                    }
                }
                true
            }
            AndroidKeyEvent.KEYCODE_BACK -> {
                when {
                    info.isOpen -> info.close() // Info 浮层优先收起
                    overlay.isOpen -> {
                        // 返回键仅收起不换台（先取消挂起的数字输入，避免 2s 后误跳转）
                        numberInput.clear()
                        overlay.close()
                    }
                    else -> {
                        numberInput.clear() // 先取消数字输入，再次返回才交给系统退出
                        return false
                    }
                }
                true
            }
            else -> false
        }
    }
}

private const val CHANNEL_NAME_SHOW_MS = 2_000L
private const val SIGNAL_LOST_SHOW_MS = 3_000L
private const val FAVORITE_HINT_SHOW_MS = 1_800L
private const val CHANNEL_NAME_BG_ALPHA = 0.6f
private const val SIGNAL_LOST_BG_ALPHA = 0.6f
private const val FAVORITE_HINT_BG_ALPHA = 0.8f
private const val NUMBER_BG_ALPHA = 0.8f
private const val PANEL_BG_ALPHA = 0.75f

/** 浮层高度占屏比例 */
private const val OVERLAY_HEIGHT_FRACTION = 0.55f

/** 主键盘数字键：KEYCODE_0(7)..KEYCODE_9(16) */
private val DIGIT_CODES = AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9

/** 数字小键盘：KEYCODE_NUMPAD_0(144)..KEYCODE_NUMPAD_9(153) */
private val NUMPAD_DIGIT_CODES = AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9
