package icu.gxb.hypertv.epg

/**
 * 匹配规则编排（v3）：读取全部规则并应用到当前频道。
 *
 * 规则应用时机：EPG 刷新成功后（EpgRefresher 内联应用）+ 频道导入后（路由层经
 * PlaylistImporter.onImportApplied 回调触发）+ 手动 POST /api/epg/rules/apply。
 */
class MatchRuleManager(private val store: EpgStore) {

    /** 应用全部规则，返回实际更新（产生 epgId 写入）的频道数。 */
    suspend fun applyAll(): Int {
        val channels = store.channels()
        val rules = store.matchRules()
        if (rules.isEmpty()) return 0
        val result = applyMatchRules(
            channels = channels,
            rules = rules.map { MatchRule(it.epgChannelId, it.keyword, it.ruleType) },
        )
        if (result.updates.isNotEmpty()) store.updateChannelEpgIds(result.updates)
        return result.updates.size
    }
}
