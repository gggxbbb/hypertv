package icu.gxb.hypertv.player

/**
 * 播放器操作抽象。真实实现包装单一 ExoPlayer 实例（ADR-0006 单实例复用，
 * 换台仅切换 MediaItem），单测注入 fake 以驱动状态机。
 */
interface PlayerOperations {

    /** 播放器事件回调（由 Controller 实现并注册） */
    interface Listener {
        /** 播放成功：ExoPlayer 到达 STATE_READY */
        fun onPlayerReady()

        /** 播放失败：ExoPlayer 上报 PlaybackException（对应 STATE_IDLE + playerError） */
        fun onPlayerError()
    }

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** 切换媒体源（只切 MediaItem，不重建播放器实例） */
    fun setMediaItem(url: String)

    fun prepare()
    fun play()

    /** 停止播放并复位错误状态（重试前调用） */
    fun stop()

    fun release()
}
