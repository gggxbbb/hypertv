package icu.gxb.hypertv.server

import icu.gxb.hypertv.epg.EpgMatchStats
import icu.gxb.hypertv.epg.EpgRefreshResult
import icu.gxb.hypertv.epg.EpgRefreshService
import icu.gxb.hypertv.epg.EpgRefreshStatus

/** 路由测试用的内存实现 [EpgRefreshService]：同步完成，记录调用并支持注入失败。 */
class FakeEpgRefreshService : EpgRefreshService {

    override val status = EpgRefreshStatus()

    var refreshGlobalCalls = 0
    val refreshGroupCalls = mutableListOf<String>()

    var globalError: Exception? = null
    val groupErrors = mutableMapOf<String, Exception>()

    fun forceRunning() {
        status.markRunning("global", System.currentTimeMillis())
    }

    override suspend fun refreshGlobal(): EpgRefreshResult {
        refreshGlobalCalls++
        globalError?.let { throw it }
        val result = EpgRefreshResult(scope = "global", stats = EpgMatchStats(10, 8, 5, 2, 1), programsWritten = 7)
        status.markSuccess(System.currentTimeMillis(), result.stats)
        return result
    }

    override suspend fun refreshGroup(groupName: String): EpgRefreshResult {
        refreshGroupCalls += groupName
        groupErrors[groupName]?.let { throw it }
        val result = EpgRefreshResult(scope = "group:$groupName", stats = EpgMatchStats(3, 3, 2, 1, 0), programsWritten = 3)
        status.markSuccess(System.currentTimeMillis(), result.stats)
        return result
    }

    override suspend fun refreshIfStale(): Boolean = false
}
