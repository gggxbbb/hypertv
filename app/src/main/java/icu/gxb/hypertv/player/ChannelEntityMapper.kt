package icu.gxb.hypertv.player

import icu.gxb.hypertv.data.entity.ChannelEntity

/**
 * Room 频道实体 → 播放器视角频道（剥离数据层字段，player 包不依赖 Room）。
 * 供 RepositoryChannelSource / RepositoryFavoriteSource 共用。
 */
internal fun ChannelEntity.toChannel(): Channel = Channel(
    id = id,
    name = name,
    url = url,
    groupName = groupName,
    logoUrl = logoUrl,
    isFavorite = isFavorite,
    orderIndex = orderIndex,
    epgId = epgId,
)
