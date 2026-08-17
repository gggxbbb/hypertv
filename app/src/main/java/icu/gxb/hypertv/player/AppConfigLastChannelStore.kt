package icu.gxb.hypertv.player

import icu.gxb.hypertv.data.repository.HypertvRepository

/**
 * 把"上次播放频道"持久化到 Room 的 app_config 表。
 * key 为 [KEY_LAST_PLAYED_CHANNEL_ID]，写入发生在播放成功（STATE_READY）时。
 */
class AppConfigLastChannelStore(
    private val repository: HypertvRepository,
) : LastChannelStore {

    override suspend fun getLastPlayedChannelId(): String? =
        repository.getConfig(KEY_LAST_PLAYED_CHANNEL_ID)

    override suspend fun saveLastPlayedChannelId(channelId: String) {
        repository.putConfig(KEY_LAST_PLAYED_CHANNEL_ID, channelId)
    }

    companion object {
        const val KEY_LAST_PLAYED_CHANNEL_ID = "last_played_channel_id"
    }
}
