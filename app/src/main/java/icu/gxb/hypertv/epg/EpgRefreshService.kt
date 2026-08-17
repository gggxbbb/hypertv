package icu.gxb.hypertv.epg

/**
 * EPG 刷新能力入口：路由与 App 启动路径只依赖该接口，刷新服务单测用内存 fake 注入。
 */
interface EpgRefreshService {

    /** 刷新状态持有者（内存态，线程安全） */
    val status: EpgRefreshStatus

    /** 刷新全局 EPG 源（作用域 = 未配置独立分组源的全部频道） */
    suspend fun refreshGlobal(): EpgRefreshResult

    /** 刷新分组级 EPG 源（未配置独立源时回退全局源） */
    suspend fun refreshGroup(groupName: String): EpgRefreshResult

    /** 启动过期即刷（ADR-0005）：距上次成功刷新 >12h 且已配置全局源时自动刷新 */
    suspend fun refreshIfStale(): Boolean
}
