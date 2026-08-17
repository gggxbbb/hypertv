package icu.gxb.hypertv.player

import android.media.AudioManager
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频焦点决策逻辑单测（纯 JVM，ticket 03）。
 *
 * 覆盖：播放→请求焦点 / 已在播放不重复请求 / 停止→释放焦点 / 未持有焦点时停止
 * 不释放 / LOSS→暂停且不自动恢复 / TRANSIENT→暂停且 GAIN 恢复 / TRANSIENT 暂停后
 * 停止清除待恢复 / CAN_DUCK 忽略。
 *
 * 常量说明：AudioManager.AUDIOFOCUS_* 与 Player.STATE_* 均为编译期常量，
 * 纯 JVM 下直接内联为数值，不会触达 android 框架 stub。
 */
class AudioFocusPolicyTest {

    // ---- 播放状态变化 → 焦点请求/释放 ----

    @Test
    fun `starting playback requests audio focus`() {
        val policy = AudioFocusPolicy()
        assertEquals(
            FocusCommand.RequestFocus,
            policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY),
        )
    }

    @Test
    fun `already playing playback state changes do not re-request`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)
        // 换台等场景再次上报（BUFFERING/READY 切换）：焦点已持有，不重复请求
        assertEquals(
            FocusCommand.NoOp,
            policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_BUFFERING),
        )
    }

    @Test
    fun `stop releases audio focus`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)

        assertEquals(
            FocusCommand.AbandonFocus,
            policy.onIsPlayingChanged(isPlaying = false, playbackState = Player.STATE_IDLE),
        )
    }

    @Test
    fun `stop without held focus does not abandon`() {
        val policy = AudioFocusPolicy()
        // 从未播放过（或焦点已被系统拿走）直接停止：无焦点可释放
        assertEquals(
            FocusCommand.NoOp,
            policy.onIsPlayingChanged(isPlaying = false, playbackState = Player.STATE_IDLE),
        )
    }

    @Test
    fun `pause while ready does not abandon focus`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)

        // 焦点丢失导致的暂停只改 isPlaying、不改 playbackState（仍 READY）：不释放焦点
        assertEquals(
            FocusCommand.NoOp,
            policy.onIsPlayingChanged(isPlaying = false, playbackState = Player.STATE_READY),
        )
    }

    // ---- 焦点回调 → 暂停/恢复/忽略 ----

    @Test
    fun `permanent loss pauses playback and does not auto resume`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)

        assertEquals(
            FocusReaction.Pause,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS),
        )
        // LOSS 是永久丢失：即使之后收到 GAIN（理论 AudioManager 不会发）也不自动恢复
        assertEquals(
            FocusReaction.NoOp,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `permanent loss means next playback re-requests focus`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // LOSS 后再次播放（如用户手动恢复）：重新请求焦点
        assertEquals(
            FocusCommand.RequestFocus,
            policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY),
        )
    }

    @Test
    fun `transient loss pauses playback`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)

        assertEquals(
            FocusReaction.Pause,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun `gain after transient loss resumes playback`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        // 其他 App 释放焦点 → 本 App 恢复
        assertEquals(
            FocusReaction.Resume,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `gain without pending pause is ignored`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)

        // requestAudioFocus 成功后系统同步回调 GAIN：播放器本就在播放，忽略
        assertEquals(
            FocusReaction.NoOp,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `transient loss paused then stop clears pending resume`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        // 焦点未回来期间用户退出播放（stop → IDLE）：清除待恢复标记
        policy.onIsPlayingChanged(isPlaying = false, playbackState = Player.STATE_IDLE)

        // 之后（理论）收到 GAIN：已停止，不自动恢复
        assertEquals(
            FocusReaction.NoOp,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `can duck is ignored keeping playback`() {
        val policy = AudioFocusPolicy()
        policy.onIsPlayingChanged(isPlaying = true, playbackState = Player.STATE_READY)

        // 直播语义：忽略 CAN_DUCK，不降音量也不暂停
        assertEquals(
            FocusReaction.Ignore,
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }
}
