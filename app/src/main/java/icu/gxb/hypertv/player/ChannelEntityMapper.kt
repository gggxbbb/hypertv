package icu.gxb.hypertv.player

import icu.gxb.hypertv.data.entity.ChannelEntity

/**
 * Room 频道实体 → 播放器视角频道（剥离数据层字段，player 包不依赖 Room）。
 * 供 RepositoryChannelSource / RepositoryFavoriteSource 共用。
 *
 * @param number 频道号 = 在排序后（已过滤隐藏）列表中的位置 + 1，由调用方按 index 传入
 */
internal fun ChannelEntity.toChannel(number: Int): Channel = Channel(
    id = id,
    name = name,
    url = url,
    groupName = groupName,
    logoUrl = logoUrl,
    isFavorite = isFavorite,
    orderIndex = orderIndex,
    number = number,
    epgId = epgId,
)
