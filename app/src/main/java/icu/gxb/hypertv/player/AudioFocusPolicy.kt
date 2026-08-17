package icu.gxb.hypertv.player

import android.media.AudioManager
import androidx.media3.common.Player

/**
 * 音频焦点决策逻辑（纯逻辑，无 Android 运行时依赖，便于纯 JVM 单测）。
 *
 * 由 [AudioFocusController] 调用：把"播放器状态变化"和"AudioManager 焦点回调"
 * 翻译成对外部世界的命令（请求/释放焦点、暂停/恢复播放）。Controller 只负责
 * 执行命令，本类负责"何时做、做什么"的判定。
 *
 * 状态机：
 * - 开始播放（isPlaying=true）且尚未持有焦点 → 请求 AUDIOFOCUS_GAIN
 * - 停止播放（isPlaying=false 且 playbackState == IDLE，即 PlayerController.stop
 *   的退出路径）→ 释放焦点，并清除"因焦点丢失而暂停"的待恢复标记
 * - 焦点事件：LOSS / LOSS_TRANSIENT → 暂停（直播流暂停画面，可接受）；
 *   GAIN → 仅当之前是因焦点丢失而暂停（TRANSIENT 恢复）才恢复播放；
 *   LOSS（永久）后不自动恢复；CAN_DUCK 忽略（保持播放，直播语义合理）。
 *
 * 已知取舍：焦点丢失触发的暂停/恢复直接调 player.pause()/play()，绕过
 * PlayerController 状态机——PlayerController.state 仍停留在 Playing。这是有意为之：
 * 换台会重新 setMediaItem+prepare+play（重新请求焦点），频道名浮层/换台逻辑不受影响；
 * 且 pause 不改 playbackState（仍 READY），不会触发上述 IDLE 释放路径。
 *
 * 简化假设：requestAudioFocus 被判定为需要时即视为"持有焦点"（holdingFocus=true）。
 * 若系统拒绝（罕见），本次状态可能短暂不一致，但下次 isPlaying 变化会重新请求，
 * 可自愈；拒绝时 Controller 会打日志。
 */
class AudioFocusPolicy {

    /** 当前是否持有音频焦点（self-reported，见类注释的简化假设） */
    private var holdingFocus = false

    /** 是否因焦点丢失（TRANSIENT）而暂停，等待 GAIN 后恢复 */
    private var pausedByFocusLoss = false

    /**
     * 播放器播放状态变化（onIsPlayingChanged / onPlaybackStateChanged 均以最新
     * isPlaying + playbackState 调用本方法，policy 幂等，重复上报无害）。
     *
     * @return 对 AudioManager 的命令：请求/释放焦点，或无需动作
     */
    fun onIsPlayingChanged(isPlaying: Boolean, playbackState: Int): FocusCommand {
        if (isPlaying) {
            if (!holdingFocus) {
                holdingFocus = true
                return FocusCommand.RequestFocus
            }
            return FocusCommand.NoOp
        }
        // 停止路径（PlayerController.stop → player.stop → STATE_IDLE）：释放焦点
        if (playbackState == Player.STATE_IDLE) {
            pausedByFocusLoss = false
            if (holdingFocus) {
                holdingFocus = false
                return FocusCommand.AbandonFocus
            }
        }
        return FocusCommand.NoOp
    }

    /**
     * AudioManager 焦点回调（onAudioFocusChange）。
     *
     * @return 对播放器的反应：暂停/恢复/忽略
     */
    fun onAudioFocusChange(focusChange: Int): FocusReaction {
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久丢失：暂停且不置"待恢复"标记（不自动恢复）
                holdingFocus = false
                FocusReaction.Pause
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 临时丢失：暂停，等待 GAIN 回来恢复
                holdingFocus = false
                pausedByFocusLoss = true
                FocusReaction.Pause
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    FocusReaction.Resume
                } else {
                    // 请求成功后系统同步回调 GAIN：播放器本就在播放，忽略
                    FocusReaction.NoOp
                }
            }
            // CAN_DUCK 等：忽略（直播不降音量，保持播放）
            else -> FocusReaction.Ignore
        }
    }
}

/** 播放状态变化 → 对 AudioManager 的命令 */
sealed interface FocusCommand {
    data object RequestFocus : FocusCommand
    data object AbandonFocus : FocusCommand
    data object NoOp : FocusCommand
}

/** 焦点回调 → 对播放器的反应 */
sealed interface FocusReaction {
    data object Pause : FocusReaction
    data object Resume : FocusReaction
    data object NoOp : FocusReaction
    data object Ignore : FocusReaction
}
