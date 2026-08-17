package icu.gxb.hypertv.player

/**
 * 上次播放频道的持久化抽象。真实实现落到 app_config 表（key: last_played_channel_id），
 * 单测注入 fake。
 */
interface LastChannelStore {
    suspend fun getLastPlayedChannelId(): String?
    suspend fun saveLastPlayedChannelId(channelId: String)
}
