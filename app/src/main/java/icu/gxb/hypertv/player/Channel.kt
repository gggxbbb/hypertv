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
)
