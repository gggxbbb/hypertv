package icu.gxb.hypertv.player

/**
 * 电视端播放状态机（ticket 04）。UI 观察 [PlayerController.state] 渲染浮层与路由。
 */
sealed interface PlayerState {

    /** 无播放目标（频道列表为空或播放已停止） */
    data object Idle : PlayerState

    /** 正在准备指定频道（启动/换台/重试播放） */
    data class Preparing(val channelId: String) : PlayerState

    /** 指定频道正在播放（ExoPlayer 到达 STATE_READY） */
    data class Playing(val channelId: String) : PlayerState

    /**
     * 播放失败，正在等待第 attempt 次重试（间隔 2s，ADR-0007）。
     * attempt ∈ 1..maxRetries；重试耗尽仍失败 → 自动跳下一个频道。
     */
    data class ErrorRetrying(
        val channelId: String,
        val attempt: Int,
        val maxRetries: Int,
    ) : PlayerState

    /** 重试耗尽，正在自动切到下一个频道（ADR-0007）。UI 据此显示"信号中断"提示。 */
    data class AutoAdvancing(
        val fromChannelId: String,
        val toChannelId: String,
    ) : PlayerState
}
