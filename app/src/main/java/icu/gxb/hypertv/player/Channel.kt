package icu.gxb.hypertv.player

/**
 * 播放器视角的频道。从 Room 实体剥离字段，降低 player 包对数据层的耦合，
 * 便于 JVM 单测构造 fake 数据。
 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val groupName: String,
    /** 台标 URL（浮层列表展示，Coil 异步加载），可能为空 */
    val logoUrl: String? = null,
    /** 收藏标记（浮层"收藏"标签过滤） */
    val isFavorite: Boolean = false,
    /** 全局稳定排序位置，频道号 = orderIndex + 1（与分组无关） */
    val orderIndex: Int = 0,
    /** EPG 匹配回写后的 channelEpgId（Info 浮层/节目表按它查节目），可能为空 */
    val epgId: String? = null,
)
