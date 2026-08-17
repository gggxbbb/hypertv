package icu.gxb.hypertv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 电视端播放状态机（ticket 04）。
 *
 * - **单实例**：换台/启动/重试都只调用 setMediaItem + prepare + play，
 *   不销毁重建 ExoPlayer（ADR-0006）。
 * - **开机自动播放**：[start] 后观察频道列表，读 `last_played_channel_id`
 *   （不存在则播列表第一个可见频道）；[onPlayerReady] 时写入该 key。
 * - **失败重试**：[onPlayerError] 对当前频道重试 [maxRetries] 次（间隔
 *   [retryDelayMs]），耗尽后自动切列表下一个频道（ADR-0007）；任何新指令
 *   （play/switchTo/switchToIndex）都会取消挂起的重试任务，观众可随时打断。
 *
 * 所有内部状态变更与播放器调用都发生在 [scope] 的调度上下文（生产环境为
 * Main.immediate，即 ExoPlayer 要求的应用主线程），Room 频道流发射到同一线程。
 */
class PlayerController(
    private val player: PlayerOperations,
    private val channelSource: ChannelSource,
    private val groupSource: GroupSource,
    private val lastChannelStore: LastChannelStore,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val maxRetries: Int = MAX_RETRIES,
) : PlayerOperations.Listener {

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** 缓存的可见频道列表（MainActivity 路由 + 频道名浮层） */
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** 缓存的可见分组名列表（按 orderIndex 升序，频道列表浮层标签行，ticket 05） */
    private val _groups = MutableStateFlow<List<String>>(emptyList())
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    private var started = false
    private var autoPlayStarted = false
    private var retryJob: Job? = null
    private var retryCount = 0
    private var currentChannelId: String? = null

    init {
        player.addListener(this)
    }

    /** 由应用启动时调用：开始观察频道列表与分组列表并触发开机自动播放 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            channelSource.visibleChannels.collect { list -> onChannelsUpdated(list) }
        }
        scope.launch {
            groupSource.groups.collect { list -> _groups.value = list }
        }
    }

    /** 退出播放页时停止播放：取消重试、复位当前频道与状态（等价于内部 [stopPlayback]） */
    fun stop() {
        stopPlayback()
    }

    /**
     * 重新进入播放页时恢复上次播放：仅当处于 Idle 且已有频道缓存时，
     * 重新触发开机自动播放（读上次频道，不存在播第一个）。
     *
     * 竞态：首次冷启动时频道列表可能尚未从 Room 发射（channels 为空），
     * 此时静默跳过，由 [start] 的流发射走既有自动播放路径，保证不双播不漏播；
     * 播放中（非 Idle）调用也会被跳过，避免打断当前播放。
     */
    fun resumeIfIdle() {
        if (_state.value != PlayerState.Idle) return
        val list = _channels.value
        if (list.isEmpty()) return
        scope.launch { autoStart(list) }
    }

    // ---- 指令 ----

    /** 播放指定频道（按 id 在缓存列表中定位；找不到则忽略） */
    fun play(channelId: String) {
        val index = indexOf(channelId)
        if (index >= 0) playAt(index, PlayerState.Preparing(channelId))
    }

    /** 按列表顺序换台：delta = ±1（可任意整数），超出列表自动回绕 */
    fun switchTo(delta: Int) {
        if (_channels.value.isEmpty()) return
        val current = currentIndex()
        val size = _channels.value.size
        val next = when {
            current < 0 -> if (delta > 0) 0 else size - 1
            else -> (current + delta).mod(size)
        }
        playAt(next, PlayerState.Preparing(_channels.value[next].id))
    }

    /** 按绝对索引换台（频道号 = index + 1），超出列表自动回绕 */
    fun switchToIndex(index: Int) {
        if (_channels.value.isEmpty()) return
        val size = _channels.value.size
        playAt(index.mod(size), PlayerState.Preparing(_channels.value[index.mod(size)].id))
    }

    // ---- 播放器回调（PlayerOperations.Listener，生产环境在主线程） ----

    /** 播放成功（STATE_READY）：更新状态并持久化"上次播放频道" */
    override fun onPlayerReady() {
        val id = currentChannelId ?: return
        retryCount = 0
        _state.value = PlayerState.Playing(id)
        scope.launch { lastChannelStore.saveLastPlayedChannelId(id) }
    }

    /** 播放失败：重试当前频道，或重试耗尽后自动跳下一个（ADR-0007） */
    override fun onPlayerError() {
        val id = currentChannelId ?: return
        if (retryCount < maxRetries) {
            retryCount++
            _state.value = PlayerState.ErrorRetrying(id, retryCount, maxRetries)
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(retryDelayMs)
                val channel = channelById(id) ?: return@launch
                player.stop()
                startPlayback(channel, PlayerState.Preparing(channel.id))
            }
        } else {
            autoAdvance()
        }
    }

    // ---- 内部 ----

    private suspend fun onChannelsUpdated(list: List<Channel>) {
        _channels.value = list
        if (list.isEmpty()) {
            if (currentChannelId != null) stopPlayback()
            return
        }
        if (!autoPlayStarted) {
            autoPlayStarted = true
            autoStart(list)
            return
        }
        // 当前频道被移除（如直播源被删除）：停止并自动播新列表第一个
        val currentId = currentChannelId
        if (currentId != null && list.none { it.id == currentId }) {
            stopPlayback()
            playAt(0, PlayerState.Preparing(list.first().id))
        }
    }

    /** 开机自动播放：读上次频道，不存在或不可见则播第一个 */
    private suspend fun autoStart(list: List<Channel>) {
        val lastId = lastChannelStore.getLastPlayedChannelId()
        val targetIndex = lastId
            ?.let { id -> list.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val target = list[targetIndex]
        currentChannelId = target.id
        startPlayback(target, PlayerState.Preparing(target.id))
    }

    /** 重试耗尽：自动切到列表下一个频道（回绕）；仅一个频道时放弃并停止，避免无限循环 */
    private fun autoAdvance() {
        val id = currentChannelId ?: return
        val list = _channels.value
        if (list.isEmpty()) return
        val current = indexOf(id)
        val next = if (current < 0) 0 else (current + 1).mod(list.size)
        val nextChannel = list[next]
        if (nextChannel.id == id) {
            stopPlayback()
            return
        }
        retryCount = 0
        retryJob?.cancel()
        currentChannelId = nextChannel.id
        startPlayback(nextChannel, PlayerState.AutoAdvancing(id, nextChannel.id))
    }

    private fun playAt(index: Int, state: PlayerState) {
        if (_channels.value.isEmpty()) return
        val idx = index.mod(_channels.value.size)
        val channel = _channels.value[idx]
        retryCount = 0
        retryJob?.cancel()
        currentChannelId = channel.id
        startPlayback(channel, state)
    }

    private fun startPlayback(channel: Channel, state: PlayerState) {
        _state.value = state
        player.setMediaItem(channel.url)
        player.prepare()
        player.play()
    }

    private fun stopPlayback() {
        retryJob?.cancel()
        retryJob = null
        currentChannelId = null
        player.stop()
        _state.value = PlayerState.Idle
    }

    private fun indexOf(channelId: String): Int = _channels.value.indexOfFirst { it.id == channelId }

    private fun currentIndex(): Int = currentChannelId?.let { indexOf(it) } ?: -1

    private fun channelById(channelId: String): Channel? = _channels.value.firstOrNull { it.id == channelId }

    companion object {
        const val RETRY_DELAY_MS = 2_000L
        const val MAX_RETRIES = 3
    }
}
