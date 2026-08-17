package icu.gxb.hypertv.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/**
 * 音频焦点控制器集成单测（ticket 03，Robolectric）。
 *
 * 使用 Robolectric 的真实 AudioManager + 本地静音 mp3 文件驱动 ExoPlayer，
 * 时序可控（本地文件立即 READY，无网络依赖）。覆盖：
 * - 播放开始 → 请求 AUDIOFOCUS_GAIN（STREAM_MUSIC）
 * - 焦点事件 → 暂停 / TRANSIENT 后 GAIN 恢复 / LOSS 不自动恢复
 * - stop（退出播放）→ 释放焦点
 *
 * 决策逻辑本身（policy）的纯逻辑分支见 [AudioFocusPolicyTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioFocusControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 生成一帧静音 MP3（MPEG1 Layer III / 128kbps / 44100Hz），供 ExoPlayer 立即 READY */
    private fun silenceMp3(): ByteArray {
        val frameLen = 144 * 128_000 / 44_100 // 417 字节，无 padding
        val bytes = ByteArray(frameLen)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFB.toByte()
        bytes[2] = 0x90.toByte()
        return bytes
    }

    /**
     * 组装：先构造 [AudioFocusController]（注册 listener），再让播放器进入播放态。
     * 本地文件经 ExoPlayer 后台 Loader 线程加载，与主线程事件异步，故轮询等待 READY
     * （消除 Robolectric 下的加载竞态），随后排空主线程事件队列。
     */
    private fun setup(): Pair<ExoPlayer, ShadowAudioManager> {
        val mp3 = File(context.cacheDir, "silence.mp3").apply { writeBytes(silenceMp3()) }
        val player = ExoPlayer.Builder(context).build()
        val audioManager = context.getSystemService(AudioManager::class.java)
        AudioFocusController(player, audioManager)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(mp3)))
        player.prepare()
        player.play()
        val deadline = System.currentTimeMillis() + 5_000
        while (player.playbackState != Player.STATE_READY && System.currentTimeMillis() < deadline) {
            idleLooper()
            Thread.sleep(5)
        }
        idleLooper()
        check(player.playbackState == Player.STATE_READY) {
            "播放器未进入 READY（state=${player.playbackState}）"
        }
        return player to shadowOf(audioManager)
    }

    /** 执行主线程上排队中的播放器事件（ExoPlayer listener 经 Handler 异步分发） */
    private fun idleLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `starting playback requests audio focus`() {
        val (player, shadow) = setup()

        assertTrue("本地静音 mp3 应立即进入播放态", player.isPlaying)
        val request = shadow.lastAudioFocusRequest
        assertNotNull("播放开始应请求音频焦点", request)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.durationHint)
        assertEquals(AudioManager.STREAM_MUSIC, request.streamType)
        player.release()
    }

    @Test
    fun `focus loss pauses playback`() {
        val (player, shadow) = setup()

        val listener = shadow.lastAudioFocusRequest?.listener
            ?: error("播放开始应已请求焦点")
        // 其他 App 抢占焦点（永久丢失）→ 暂停
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse("LOSS 应暂停播放", player.isPlaying)
        assertEquals("暂停不改 playbackState", Player.STATE_READY, player.playbackState)
        player.release()
    }

    @Test
    fun `transient loss pauses then gain resumes playback`() {
        val (player, shadow) = setup()

        val listener = shadow.lastAudioFocusRequest?.listener
            ?: error("播放开始应已请求焦点")

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse("TRANSIENT 应暂停播放", player.isPlaying)

        // 其他 App 释放焦点 → 本 App 恢复
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("GAIN 应恢复播放", player.isPlaying)
        player.release()
    }

    @Test
    fun `permanent loss does not auto resume on gain`() {
        val (player, shadow) = setup()

        val listener = shadow.lastAudioFocusRequest?.listener
            ?: error("播放开始应已请求焦点")

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("LOSS 后即使收到 GAIN 也不自动恢复", player.isPlaying)
        player.release()
    }

    @Test
    fun `stop releases audio focus`() {
        val (player, shadow) = setup()
        assertNotNull(shadow.lastAudioFocusRequest)

        // 退出播放（PlayerController.stop 路径 → STATE_IDLE）
        player.stop()
        idleLooper()

        assertNotNull("停止播放应释放音频焦点", shadow.lastAbandonedAudioFocusListener)
        player.release()
    }
}
