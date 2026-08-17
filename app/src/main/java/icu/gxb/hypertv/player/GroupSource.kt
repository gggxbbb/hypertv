package icu.gxb.hypertv.player

import kotlinx.coroutines.flow.Flow

/**
 * 分组源抽象：提供按 orderIndex 升序的分组名列表。
 * Controller 观察该 Flow 并缓存当前分组列表，供频道列表浮层做标签行（ticket 05）。
 */
interface GroupSource {
    val groups: Flow<List<String>>
}
