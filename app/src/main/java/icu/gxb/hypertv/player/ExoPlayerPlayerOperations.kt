package icu.gxb.hypertv.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 把单一 ExoPlayer 实例翻译成 [PlayerOperations]，供状态机驱动。
 *
 * - 换台只切换 MediaItem，不重建播放器（ADR-0006）
 * - Media3 1.5+ 用 [Player.Listener]；错误在 onPlayerError 上报
 *   （对应 STATE_IDLE + playerError），READY 在 onPlaybackStateChanged 上报
 * - 记录 prepare→STATE_READY 耗时并打 log，为 ticket 11 换台延迟实测留数据接口
 */
class ExoPlayerPlayerOperations(
    private val player: ExoPlayer,
    private val tag: String = TAG,
) : PlayerOperations {

    private var listener: PlayerOperations.Listener? = null
    private var preparedAtMs = 0L

    private val internalListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                Log.i(tag, "media ready in ${SystemClock.elapsedRealtime() - preparedAtMs}ms")
                listener?.onPlayerReady()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(tag, "playback error: code=${error.errorCodeName} msg=${error.message}", error)
            listener?.onPlayerError()
        }
    }

    init {
        player.addListener(internalListener)
    }

    override fun addListener(listener: PlayerOperations.Listener) {
        this.listener = listener
    }

    override fun removeListener(listener: PlayerOperations.Listener) {
        if (this.listener === listener) this.listener = null
    }

    override fun setMediaItem(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
    }

    override fun prepare() {
        preparedAtMs = SystemClock.elapsedRealtime()
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.release()
    }

    private companion object {
        const val TAG = "ExoPlayerPlayerOperations"
    }
}
