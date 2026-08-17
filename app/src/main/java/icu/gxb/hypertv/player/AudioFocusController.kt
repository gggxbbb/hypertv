package icu.gxb.hypertv.player

import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频焦点控制器（ticket 03 目标②：防止与其他软件同时播放出声）。
 *
 * 通过 [Player.Listener.onIsPlayingChanged] 与 [Player.Listener.onPlaybackStateChanged]
 * 把播放状态翻译成 [AudioFocusPolicy] 的输入：
 * - 开始播放 → 请求 AUDIOFOCUS_GAIN
 * - 停止（STATE_IDLE，PlayerController.stop 路径）→ 释放焦点
 * - LOSS / LOSS_TRANSIENT → player.pause()；GAIN → 因焦点丢失而暂停时 player.play()
 *
 * 暂停/恢复绕过 PlayerController 状态机是已知取舍（见 [AudioFocusPolicy] 类注释）：
 * 换台会重新 setMediaItem+prepare+play 并重新请求焦点，频道名浮层/换台逻辑不受影响。
 *
 * 生命周期：@Singleton 随 App 进程存在（PlayerModule 装配），构造即注册 listener，
 * 不耦合 Activity（播放页/引导页切换时焦点管理持续生效）。
 *
 * [AudioManager] 由装配方注入（见 PlayerModule），测试可注入 fake。
 */
@Singleton
class AudioFocusController @Inject constructor(
    private val player: ExoPlayer,
    private val audioManager: AudioManager,
) {
    private val policy = AudioFocusPolicy()

    /** AudioManager 焦点回调：把焦点事件翻译成对播放器的暂停/恢复 */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        applyFocusReaction(policy.onAudioFocusChange(change))
    }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // 播放/停止时机判定：isPlaying 变化时用当前 playbackState 同步一次
                    applyFocusCommand(policy.onIsPlayingChanged(isPlaying, player.playbackState))
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // stop() 等路径 playbackState 先于 isPlaying 变化，双回调保证最终一致（policy 幂等）
                    applyFocusCommand(policy.onIsPlayingChanged(player.isPlaying, playbackState))
                }
            },
        )
    }

    private fun applyFocusCommand(command: FocusCommand) {
        when (command) {
            FocusCommand.RequestFocus -> {
                val result = audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    // 拒绝后 policy 的 holdingFocus 可能短暂不一致，下次 isPlaying 变化会重新请求
                    Log.w(TAG, "requestAudioFocus rejected: result=$result")
                }
            }
            FocusCommand.AbandonFocus -> audioManager.abandonAudioFocus(focusListener)
            FocusCommand.NoOp -> Unit
        }
    }

    private fun applyFocusReaction(reaction: FocusReaction) {
        when (reaction) {
            FocusReaction.Pause -> player.pause()
            FocusReaction.Resume -> player.play()
            FocusReaction.NoOp, FocusReaction.Ignore -> Unit
        }
    }

    private companion object {
        const val TAG = "AudioFocusController"
    }
}
